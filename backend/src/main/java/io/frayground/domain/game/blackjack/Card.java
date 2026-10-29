package io.frayground.domain.game.blackjack;

import java.util.List;

public record Card(int deckNo, String suit, String rank) {
  public static final List<String> SUITS = List.of("CLUBS", "DIAMONDS", "HEARTS", "SPADES");
  public static final List<String> RANKS = List.of("A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K");

  public Card {
    if (deckNo < 0 || deckNo > 5 || !SUITS.contains(suit) || !RANKS.contains(rank)) {
      throw new IllegalArgumentException("card");
    }
  }
}
