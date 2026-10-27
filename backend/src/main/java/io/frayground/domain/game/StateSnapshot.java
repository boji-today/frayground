package io.frayground.domain.game;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record StateSnapshot(
    UUID matchId, long stateVersion, Map<String, Object> state, Instant createdAt) {
  public StateSnapshot {
    Objects.requireNonNull(matchId);
    Objects.requireNonNull(state);
    Objects.requireNonNull(createdAt);
    state = Map.copyOf(state);
    if (stateVersion < 0) {
      throw new IllegalArgumentException("stateVersion");
    }
  }
}
