package io.frayground.domain.action;

/**
 * Receipt status of one action submission.
 *
 * <p>{@code EXECUTED} follows an authoritative settlement. {@code REJECTED}
 * means the action was refused and did not change the match. {@code ACCEPTED}
 * is reserved for a future simultaneous phase: the action has been collected
 * and the world has not changed. A sequential match ends as {@code EXECUTED}
 * or {@code REJECTED}.
 */
public enum SubmissionStatus {
  EXECUTED,
  REJECTED,
  ACCEPTED
}
