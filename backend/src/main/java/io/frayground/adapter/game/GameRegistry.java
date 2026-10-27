package io.frayground.adapter.game;

import io.frayground.domain.game.GameDefinition;
import io.frayground.domain.game.GameKey;
import java.util.Optional;

/**
 * Explicit whitelist of games registered by {@link GameKey}.
 *
 * <p>A missing key returns empty. The registry does not load a class by name
 * and does not enable Jackson default typing.
 */
public interface GameRegistry {
  Optional<GameDefinition<?, ?, ?>> definition(GameKey key);

  Optional<GameCodec<?, ?, ?>> codec(GameKey key);

  Optional<ActionSchemaProvider> schemaProvider(GameKey key);
}
