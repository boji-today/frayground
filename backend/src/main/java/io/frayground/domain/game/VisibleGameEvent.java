package io.frayground.domain.game;

import java.util.Map;

public record VisibleGameEvent(String type, Map<String, Object> payload) {
  public VisibleGameEvent {
    if (type == null || type.isBlank()) {
      throw new IllegalArgumentException("type");
    }
    payload = Map.copyOf(payload);
  }
}
