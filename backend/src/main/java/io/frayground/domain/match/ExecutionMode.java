package io.frayground.domain.match;

/**
 * How the room grants the next decision.
 *
 * <p>{@code AUTO} continues scheduling after a successful phase.
 * {@code STEP} grants one decision and then pauses. The mode does not say
 * whether the room is running now; that is {@link ExecutionStatus}. Switching
 * to {@code AUTO} does not leave {@code PAUSED} or {@code PAUSING}.
 */
public enum ExecutionMode {
  AUTO,
  STEP
}
