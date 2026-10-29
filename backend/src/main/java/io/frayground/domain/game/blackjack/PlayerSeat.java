package io.frayground.domain.game.blackjack;

import java.util.List;
import java.util.UUID;

public record PlayerSeat(UUID participantId, String seat, List<Card> cards, PlayerStatus status) {
  public PlayerSeat {
    if (participantId == null || seat == null || seat.isBlank() || status == null) {
      throw new IllegalArgumentException("player");
    }
    cards = List.copyOf(cards);
  }
}
