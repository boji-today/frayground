package io.frayground.adapter.game;

import io.frayground.domain.game.GameKey;
import io.frayground.domain.game.GameState;
import io.frayground.domain.game.Resolution;
import java.util.Optional;

/** Optional forced transition for a game that can settle without an agent action. */
public interface SystemPhaseHandler {
  GameKey gameKey();

  Optional<Resolution<? extends GameState>> resolve(GameState state);
}
