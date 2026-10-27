package io.frayground.domain.game;

import java.util.Map;
import java.time.Instant;
import java.util.UUID;

public record GameEvent(
    UUID matchId,
    long sequence,
    long stateVersion,
    UUID decisionId,
    String type,
    int eventVersion,
    Map<String, Object> payload,
    Instant occurredAt) {
  public GameEvent {
    if (matchId == null || sequence < 1 || stateVersion < 0 || eventVersion < 1) {
      throw new IllegalArgumentException("event");
    }
    if (type == null || type.isBlank() || occurredAt == null) {
      throw new IllegalArgumentException("event");
    }
    payload = Map.copyOf(payload);
  }
}
