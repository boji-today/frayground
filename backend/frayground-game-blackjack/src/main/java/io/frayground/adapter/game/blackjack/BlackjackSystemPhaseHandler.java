package io.frayground.adapter.game.blackjack;

import io.frayground.adapter.game.SystemPhaseHandler;
import io.frayground.domain.game.GameKey;
import io.frayground.domain.game.GameState;
import io.frayground.domain.game.Resolution;
import io.frayground.domain.game.blackjack.BlackjackDefinition;
import io.frayground.domain.game.blackjack.BlackjackState;
import java.util.Optional;

public final class BlackjackSystemPhaseHandler implements SystemPhaseHandler {
  private final BlackjackDefinition definition;

  public BlackjackSystemPhaseHandler(BlackjackDefinition definition) {
    this.definition = definition;
  }

  @Override
  public GameKey gameKey() {
    return definition.key();
  }

  @Override
  public Optional<Resolution<? extends GameState>> resolve(GameState state) {
    if (!(state instanceof BlackjackState blackjack)) {
      return Optional.empty();
    }
    return definition.resolveSystem(blackjack).map(resolution -> resolution);
  }
}
