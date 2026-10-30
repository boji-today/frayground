package io.frayground.domain.match;

import java.util.Objects;
import java.util.UUID;

/**
 * One control request against the room's current match.
 *
 * <p>A retry of the same {@code commandId} returns the original receipt.
 * {@code expectedRoomRevision} and {@code expectedMatchId} are checked after
 * that lookup. {@code mode} is present only for {@code SET_MODE}. Start and
 * step move a {@code READY} match to {@code ACTIVE}. Reset aborts the current
 * unfinished match and creates a new {@code READY} match. Once the match is
 * {@code FINISHED} or {@code ABORTED}, every command except reset is rejected.
 * {@code CANCEL_DECISION} cancels every unfinished decision in the current
 * turn, pauses the room, and does not end the match. The command does not name
 * one decision.
 */
public record ControlCommand(
    UUID commandId,
    long expectedRoomRevision,
    UUID expectedMatchId,
    ControlCommandType type,
    ExecutionMode mode) {

  public ControlCommand {
    Objects.requireNonNull(commandId);
    Objects.requireNonNull(expectedMatchId);
    Objects.requireNonNull(type);
    if (expectedRoomRevision < 0) {
      throw new IllegalArgumentException("expectedRoomRevision");
    }
    if (type == ControlCommandType.SET_MODE) {
      Objects.requireNonNull(mode);
    } else if (mode != null) {
      throw new IllegalArgumentException("mode");
    }
  }
}
