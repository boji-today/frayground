package io.frayground.domain.match;

/**
 * Lifecycle of one match, independent of room execution.
 *
 * <p>{@code READY} has not started. {@code ACTIVE} is in play. A rules win or
 * draw moves it to {@code FINISHED}. {@code ABORTED} is run termination from
 * reset, not a fabricated win. Reset is the only control accepted after
 * {@code FINISHED} or {@code ABORTED}.
 */
public enum MatchStatus {
  READY,
  ACTIVE,
  FINISHED,
  ABORTED
}
