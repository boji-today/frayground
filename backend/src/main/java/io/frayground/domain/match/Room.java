package io.frayground.domain.match;

import java.util.Objects;
import java.util.UUID;

/**
 * Session container that outlives any single match.
 *
 * <p>{@code activeMatchId} points at the current {@link Match}. Reset keeps
 * this room, aborts the previous match when it had not finished, and points
 * the field at a new {@code READY} match. {@code executionMode} ({@code AUTO}
 * or {@code STEP}) decides the next grant; {@code executionStatus}
 * ({@code PAUSED}, {@code RUNNING}, {@code PAUSING}) says whether the room is
 * running. Neither field says whether the match has ended. After a finished
 * match the room stays {@code PAUSED} and does not advance. The room does not
 * limit how many participants the active match has, and its execution status
 * covers the whole match rather than one seat. {@code health} is
 * availability ({@code OK} or {@code ERROR}), not a game result.
 * {@code revision} advances on control, applied configuration, decision
 * transitions, and settlement. {@code eventSeq} is the room-event cursor and
 * continues across matches.
 */
public record Room(
    UUID roomId,
    long revision,
    long eventSeq,
    UUID activeMatchId,
    ExecutionMode executionMode,
    ExecutionStatus executionStatus,
    RoomHealth health,
    String healthError) {
  public Room {
    Objects.requireNonNull(roomId);
    Objects.requireNonNull(executionMode);
    Objects.requireNonNull(executionStatus);
    Objects.requireNonNull(health);
    if (revision < 0 || eventSeq < 0
        || (health == RoomHealth.ERROR && healthError == null)) {
      throw new IllegalArgumentException("room");
    }
  }
}
