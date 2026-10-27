package io.frayground.application.port;

import io.frayground.domain.match.ExecutionMode;
import io.frayground.domain.match.ExecutionStatus;
import io.frayground.domain.match.RoomHealth;
import java.util.Objects;
import java.util.UUID;

/**
 * Room row held under the room lock.
 *
 * <p>{@code activeMatchId} may be absent before the first match is created.
 * {@code healthError} is required when {@code health} is {@code ERROR}.
 */
public record LockedRoom(
    UUID roomId,
    long revision,
    long eventSeq,
    UUID activeMatchId,
    ExecutionMode executionMode,
    ExecutionStatus executionStatus,
    RoomHealth health,
    String healthError) {

  public LockedRoom {
    Objects.requireNonNull(roomId);
    Objects.requireNonNull(executionMode);
    Objects.requireNonNull(executionStatus);
    Objects.requireNonNull(health);
    if (revision < 0) {
      throw new IllegalArgumentException("revision");
    }
    if (eventSeq < 0 || (health == RoomHealth.ERROR && healthError == null)) {
      throw new IllegalArgumentException("room");
    }
  }
}
