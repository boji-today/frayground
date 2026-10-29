package io.frayground.adapter.game.blackjack;

import io.frayground.application.port.DecisionGameMetadata;
import io.frayground.domain.game.blackjack.BlackjackDefinition;

public final class BlackjackDecisionMetadata implements DecisionGameMetadata {
  @Override
  public io.frayground.domain.game.GameKey gameKey() {
    return BlackjackDefinition.KEY;
  }

  @Override
  public String rulesText() {
    return "1到3名玩家共享一靴牌，按座位顺序要牌或停牌，并与按S17自动行动的庄家比较。";
  }

  @Override
  public long roundNo(long turnNo) {
    if (turnNo < 1) {
      throw new IllegalArgumentException("turnNo");
    }
    return 1;
  }
}
