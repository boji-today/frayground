package io.frayground.domain.game.blackjack;

import io.frayground.domain.game.GameAction;

public record BlackjackAction(String type) implements GameAction {
  public BlackjackAction {
    if (type == null || type.isBlank()) {
      throw new IllegalArgumentException("action");
    }
  }
}
