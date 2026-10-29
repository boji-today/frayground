package io.frayground.application.port;

import io.frayground.domain.game.GameKey;

/** Decision-facing information supplied by each registered game. */
public interface DecisionGameMetadata {
  GameKey gameKey();
  String rulesText();
  long roundNo(long turnNo);
}
