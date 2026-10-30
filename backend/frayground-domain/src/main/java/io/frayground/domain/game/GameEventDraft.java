package io.frayground.domain.game;

import java.util.Map;

/**
 * Fact produced by the rules during settlement.
 *
 * <p>It has a type, an event schema version, and a payload. Match identity,
 * sequence, state version, and decision are attached when the fact is
 * recorded as a {@link GameEvent}.
 */
public record GameEventDraft(String type, int eventVersion, Map<String, Object> payload) {
  public GameEventDraft {
    if (type == null || type.isBlank() || eventVersion < 1) {
      throw new IllegalArgumentException("event");
    }
    payload = Map.copyOf(payload);
  }
}
