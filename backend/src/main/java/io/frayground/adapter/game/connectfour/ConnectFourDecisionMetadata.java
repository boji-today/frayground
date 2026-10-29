package io.frayground.adapter.game.connectfour;

import io.frayground.application.port.DecisionGameMetadata;
import io.frayground.domain.game.GameKey;

public final class ConnectFourDecisionMetadata implements DecisionGameMetadata {
  @Override public GameKey gameKey() { return new GameKey("connect-four", "1"); }
  @Override public String rulesText() {
    return "两名参与者轮流在未满的列落子；先连成四子者获胜，棋盘填满则平局。";
  }
  @Override public long roundNo(long turnNo) {
    if (turnNo < 1) throw new IllegalArgumentException("turnNo");
    return turnNo / 2 + turnNo % 2;
  }
}
