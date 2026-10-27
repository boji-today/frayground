package io.frayground.agent;

import java.util.concurrent.CompletionStage;

/**
 * Orchestrates one decision through runtime, parsing, and submission.
 *
 * <p>It returns the candidate together with the authoritative receipt, or a
 * runtime error when nothing was submitted. It does not change the world
 * except through {@code ActionSubmitter}.
 */
public interface AgentRunner {
  CompletionStage<DecisionOutcome> run(RuntimeInput input, CancellationSignal cancellation);
}
