package io.frayground.domain.match;

/**
 * Whether the room is currently advancing the match.
 *
 * <p>Orthogonal to {@link MatchStatus}. {@code PAUSED} accepts a new grant.
 * {@code RUNNING} has an open grant. {@code PAUSING} waits for the current
 * decision to finish, then becomes {@code PAUSED}, and does not cancel that
 * decision. A finished match stays {@code PAUSED} and does not advance.
 */
public enum ExecutionStatus {
  PAUSED,
  RUNNING,
  PAUSING
}
