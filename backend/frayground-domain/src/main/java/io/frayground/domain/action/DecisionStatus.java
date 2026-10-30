package io.frayground.domain.action;

/**
 * Lifecycle of one decision attempt by one participant.
 *
 * <p>{@code PENDING} is created before the attempt is bound. {@code RUNNING}
 * fixes the observation, prompt version, configuration version, and deadline.
 * {@code COMPLETED}, {@code FAILED}, {@code EXPIRED}, and {@code CANCELLED}
 * are terminal and do not return to {@code RUNNING}. A late response may be
 * audited and does not change the recorded outcome. Cancelling a decision
 * does not finish the match.
 */
public enum DecisionStatus {
  PENDING,
  RUNNING,
  COMPLETED,
  FAILED,
  EXPIRED,
  CANCELLED
}
