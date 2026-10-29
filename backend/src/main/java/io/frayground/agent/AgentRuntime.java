package io.frayground.agent;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;

/**
 * Executes one decision and returns a candidate or a failure.
 *
 * <p>It does not modify the match. Submitting the action is the runner's
 * responsibility.
 */
public interface AgentRuntime {
  CompletionStage<RuntimeOutcome> decide(RuntimeInput input, CancellationSignal cancellation);

  default CompletionStage<RuntimeExecutionResult> decideWithEvidence(RuntimeInput input, CancellationSignal cancellation) {
    return decide(input, cancellation).thenApply(outcome -> new RuntimeExecutionResult(outcome, null));
  }
}
