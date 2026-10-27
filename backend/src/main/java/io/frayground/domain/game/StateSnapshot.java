package io.frayground.domain.game;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Persisted rules state at one {@code stateVersion}.
 *
 * <p>{@code state} is the encoded state for that match and version, not a
 * live {@link GameState}. Reading a snapshot does not call a model. The
 * initial version is 0.
 */
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
