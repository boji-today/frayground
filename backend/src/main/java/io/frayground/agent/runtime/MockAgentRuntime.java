package io.frayground.agent.runtime;

import io.frayground.agent.AgentRuntime;
import io.frayground.agent.CancellationSignal;
import io.frayground.agent.RuntimeError;
import io.frayground.agent.RuntimeInput;
import io.frayground.agent.RuntimeOutcome;
import io.frayground.agent.input.DecisionInputReader;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Deterministic runtime that selects the first provided option without claiming it is legal. */
public final class MockAgentRuntime implements AgentRuntime {
  private final DecisionInputReader reader;
  private final Clock clock;
  public MockAgentRuntime(DecisionInputReader reader, Clock clock) { this.reader = reader; this.clock = clock; }

  @Override public CompletionStage<RuntimeOutcome> decide(RuntimeInput input, CancellationSignal cancellation) {
    RuntimeOutcome outcome = null;
    try {
      outcome = check(cancellation, input.publicRequest().deadlineAt());
      if (outcome == null) {
        DecisionInputReader.ParsedSpecification spec = reader.readSpecification(input.publicRequest());
        if (!spec.mode().equals("ACTION_SELECTION")) outcome = failed("ACTION_MODE_NOT_SUPPORTED");
        else if (spec.options().isEmpty()) outcome = failed("NO_ACTION_OPTIONS");
        else outcome = new RuntimeOutcome.SelectedAction(spec.options().get(0).get("selectedActionId").textValue());
      }
    } catch (RuntimeException e) { outcome = failed("INVALID_DECISION_INPUT"); }
    RuntimeOutcome finalCheck = check(cancellation, input.publicRequest().deadlineAt());
    if (finalCheck != null) outcome = finalCheck;
    return CompletableFuture.completedFuture(outcome);
  }

  private RuntimeOutcome check(CancellationSignal cancellation, java.time.Instant deadline) {
    if (cancellation.isCancelled()) return failed("DECISION_CANCELLED");
    if (!clock.instant().isBefore(deadline)) return failed("DECISION_EXPIRED");
    return null;
  }
  private static RuntimeOutcome failed(String code) { return new RuntimeOutcome.Failed(new RuntimeError(code, code)); }
}
