package io.frayground.application.port;

import io.frayground.agent.RuntimeOutcome;
import io.frayground.agent.RuntimeError;
import io.frayground.agent.ModelInvocationResult;
import io.frayground.domain.action.ActionEnvelope;
import java.util.Optional;
import java.util.UUID;

/** Process-local recovery of a runtime output before durable audit and envelope writes. */
public interface PendingDecisionOutputStore {
  sealed interface Output permits Candidate, Failure { UUID decisionId(); }
  record Candidate(UUID decisionId, RuntimeOutcome candidate, ActionEnvelope envelope, String actionJson, ModelInvocationResult evidence) implements Output {
    public Candidate(UUID decisionId, RuntimeOutcome candidate, ActionEnvelope envelope, String actionJson) { this(decisionId,candidate,envelope,actionJson,null); }
  }
  record Failure(UUID decisionId, RuntimeError error, ModelInvocationResult evidence) implements Output {
    public Failure(UUID decisionId, RuntimeError error) { this(decisionId,error,null); }
  }
  Output stageAndAcquire(Output output);
  Output replaceAcquired(Output output);
  Optional<Output> acquire(UUID decisionId);
  boolean contains(UUID decisionId);
  void release(UUID decisionId, boolean completed);
}
