package io.frayground.agent;

import java.util.Objects;

/** Runtime output together with optional evidence from a single model call. */
public record RuntimeExecutionResult(RuntimeOutcome outcome, ModelInvocationResult modelResult) {
  public RuntimeExecutionResult { Objects.requireNonNull(outcome); }
}
