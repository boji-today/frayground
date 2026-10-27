package io.frayground.domain.game;

import java.util.Map;

/**
 * Game fact one seat is allowed to see.
 *
 * <p>{@link GameDefinition#visibleHistory} omits facts hidden from that seat.
 * {@code payload} is the visible projection, not necessarily the recorded
 * event.
 */
public record VisibleGameEvent(String type, Map<String, Object> payload) {
  public VisibleGameEvent {
    if (type == null || type.isBlank()) {
      throw new IllegalArgumentException("type");
    }
    payload = Map.copyOf(payload);
  }
}
