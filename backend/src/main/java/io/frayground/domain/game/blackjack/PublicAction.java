package io.frayground.domain.game.blackjack;

public record PublicAction(String type, String seat) {
  public PublicAction {
    if (type == null || type.isBlank() || seat == null || seat.isBlank()) {
      throw new IllegalArgumentException("action");
    }
  }
}
