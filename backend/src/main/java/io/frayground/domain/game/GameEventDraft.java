package io.frayground.domain.game;

import java.util.Map;

public record GameEventDraft(String type, int eventVersion, Map<String, Object> payload) {
  public GameEventDraft {
    if (type == null || type.isBlank() || eventVersion < 1) {
      throw new IllegalArgumentException("event");
    }
    payload = Map.copyOf(payload);
  }
}
