package io.frayground.agent;

/**
 * Whether the current decision has been cancelled.
 *
 * <p>The runtime and the model adapter check it so a cancelled decision can
 * stop waiting. Cancellation does not by itself change the match.
 */
public interface CancellationSignal {
  boolean isCancelled();
}
