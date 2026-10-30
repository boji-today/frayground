package io.frayground.domain.match;

/**
 * Availability of a room, not a game result or execution state.
 *
 * <p>{@code OK} accepts control and advancement. {@code ERROR} means recovery,
 * snapshot, rules version, or persistence has failed; advancement is refused
 * and recorded facts stay in place. No automatic {@code ERROR} to {@code OK}
 * transition is defined.
 */
public enum RoomHealth {
  OK,
  ERROR
}
