package io.frayground.domain.game.blackjack;

import io.frayground.domain.game.GameResult;
import io.frayground.domain.game.GameState;
import java.util.List;
import java.util.UUID;

public record BlackjackState(
    BlackjackPhase phase,
    List<UUID> participantOrder,
    List<String> seats,
    UUID currentParticipantId,
    long seed,
    String shuffleAlgorithm,
    List<Card> shoe,
    int cursor,
    List<PlayerSeat> players,
    List<Card> dealerCards,
    boolean holeHidden,
    List<PublicAction> actions,
    GameResult result) implements GameState {
  public BlackjackState {
    if (phase == null || result == null) {
      throw new IllegalArgumentException("state");
    }
    participantOrder = List.copyOf(participantOrder);
    seats = List.copyOf(seats);
    shuffleAlgorithm = shuffleAlgorithm == null ? "" : shuffleAlgorithm;
    shoe = List.copyOf(shoe);
    players = List.copyOf(players);
    dealerCards = List.copyOf(dealerCards);
    actions = List.copyOf(actions);
    BlackjackRules.check(phase, participantOrder, seats, currentParticipantId, seed, shuffleAlgorithm,
        shoe, cursor, players, dealerCards, holeHidden, actions, result);
  }
}
