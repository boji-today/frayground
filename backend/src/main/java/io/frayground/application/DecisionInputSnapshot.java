package io.frayground.application;

import io.frayground.agent.DecisionRequest;
import io.frayground.agent.HostedBindings;
import io.frayground.agent.RuntimeInput;
import io.frayground.engine.StartedDecision;
import java.util.Objects;
import java.util.Optional;

/** Input fixed at the decision boundary. */
public record DecisionInputSnapshot(StartedDecision started, DecisionRequest request, HostedBindings bindings) {
  public DecisionInputSnapshot {
    Objects.requireNonNull(started);
    Objects.requireNonNull(request);
    Objects.requireNonNull(bindings);
    if (!started.decisionId().equals(request.decisionId()) || !started.matchId().equals(request.matchId())
        || !started.turnId().equals(request.turnId()) || !started.participantId().equals(request.participantId())
        || started.stateVersion()!=request.stateVersion() || started.observationVersion()!=request.observationVersion()
        || !started.observationJson().equals(request.observationJson())
        || !started.actionSpecificationJson().equals(request.actionSpecificationJson())
        || !started.deadline().equals(request.deadlineAt())
        || !started.promptVersionId().equals(bindings.prompt().promptVersionId())
        || !started.configVersionId().equals(bindings.configuration().configVersionId())
        || !bindings.prompt().agentId().equals(bindings.configuration().agentId()))
      throw new IllegalArgumentException("Inconsistent decision input");
  }
  public RuntimeInput runtimeInput() { return new RuntimeInput(request, Optional.of(bindings)); }
}
