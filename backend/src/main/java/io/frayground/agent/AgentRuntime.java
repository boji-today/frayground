package io.frayground.agent;

import java.util.concurrent.CompletionStage;

/**
 * Executes one decision and returns a candidate or a failure.
 *
 * <p>It does not modify the match. Submitting the action is the runner's
 * responsibility.
 */
public interface AgentRuntime {
  CompletionStage<RuntimeOutcome> decide(RuntimeInput input, CancellationSignal cancellation);
}
