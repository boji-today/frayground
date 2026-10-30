package io.frayground.domain.game.blackjack;

import java.util.List;

public record ShoeShuffle(long seed, List<Card> cards) {
  public ShoeShuffle {
    cards = List.copyOf(cards);
  }
}
