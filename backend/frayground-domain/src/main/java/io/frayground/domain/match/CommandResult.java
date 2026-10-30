package io.frayground.domain.match;

import java.util.Objects;
import java.util.UUID;

/**
 * Deterministic receipt of one {@link ControlCommand}.
 *
 * <p>A null {@code errorCode} means the command completed. {@code matchId} is
 * required; after reset it is the new match. {@code roomRevision} is the room
 * revision stored with the receipt. The HTTP status stays on the command
 * receipt record and is not a field here.
 */
public record CommandResult(
    UUID commandId,
    String errorCode,
    long roomRevision,
    UUID matchId,
    ExecutionMode executionMode,
    ExecutionStatus executionStatus) {

  public CommandResult {
    Objects.requireNonNull(commandId);
    Objects.requireNonNull(matchId);
    Objects.requireNonNull(executionMode);
    Objects.requireNonNull(executionStatus);
    if (roomRevision < 0) {
      throw new IllegalArgumentException("roomRevision");
    }
    if (errorCode != null && errorCode.isBlank()) {
      throw new IllegalArgumentException("errorCode");
    }
  }
}
