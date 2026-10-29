package io.frayground.domain.game.blackjack;

import io.frayground.domain.game.GameObservation;
import java.util.List;

public record BlackjackObservation(
    String yourSeat,
    String currentSeat,
    List<SeatView> players,
    List<VisibleCard> dealerCards,
    boolean holeHidden,
    Integer dealerTotal,
    List<PublicAction> actions,
    List<String> legalActions) implements GameObservation {
  public BlackjackObservation {
    players = List.copyOf(players);
    dealerCards = List.copyOf(dealerCards);
    actions = List.copyOf(actions);
    legalActions = List.copyOf(legalActions);
  }

  public record VisibleCard(String suit, String rank) {}

  public record SeatView(String seat, List<VisibleCard> cards, PlayerStatus status, int total) {
    public SeatView {
      cards = List.copyOf(cards);
    }
  }
}
