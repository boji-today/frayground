package io.frayground.domain.game;

import java.util.Map;
import java.time.Instant;
import java.util.UUID;

/**
 * Recorded game fact, identified by match and {@code sequence}.
 *
 * <p>It is an objective rules fact, not a model intention and not the full
 * audit log. {@code decisionId} may be absent when the fact is not tied to
 * one decision. {@code stateVersion} is the version this fact belongs to.
 */
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
