package io.frayground.domain.match;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Persisted room feed event, identified by room and {@code sequence}.
 *
 * <p>The sequence continues across matches and is not {@code stateVersion} or
 * {@code roomRevision}. {@code matchId} may be absent. {@code type} is the
 * event name carried in the payload's projection; it is not a closed enum
 * here. The event is an administrative push fact, separate from a
 * {@link io.frayground.domain.game.GameEvent}.
 */
public record RoomEvent(
    UUID roomId,
    long sequence,
    UUID matchId,
    long roomRevision,
    String type,
    Map<String, Object> payload,
    Instant occurredAt) {
  public RoomEvent {
    Objects.requireNonNull(roomId);
    Objects.requireNonNull(type);
    Objects.requireNonNull(occurredAt);
    payload = Map.copyOf(payload);
    if (sequence < 1 || roomRevision < 0 || type.isBlank()) {
      throw new IllegalArgumentException("event");
    }
  }
}
