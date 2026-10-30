package io.frayground.adapter.game;

import io.frayground.domain.game.GameKey;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable whitelist indexed by the complete game type and rules version. */
public final class DefaultGameRegistry implements GameRegistry {
  private final Map<GameKey, GameRegistration<?, ?, ?>> registrations;

  public DefaultGameRegistry(Collection<GameRegistration<?, ?, ?>> registrations) {
    Objects.requireNonNull(registrations);
    Map<GameKey, GameRegistration<?, ?, ?>> indexed = new LinkedHashMap<>();
    for (GameRegistration<?, ?, ?> registration : registrations) {
      Objects.requireNonNull(registration);
      GameKey key = registration.definition().key();
      if (indexed.putIfAbsent(key, registration) != null) {
        throw new IllegalArgumentException("duplicate game registration: " + key);
      }
    }
    this.registrations = Map.copyOf(indexed);
  }

  @Override
  public Optional<GameRegistration<?, ?, ?>> registration(GameKey key) {
    return Optional.ofNullable(registrations.get(Objects.requireNonNull(key)));
  }
}
