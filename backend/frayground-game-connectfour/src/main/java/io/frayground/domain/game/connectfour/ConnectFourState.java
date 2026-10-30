package io.frayground.domain.game.connectfour;

import io.frayground.domain.game.GameResult;
import io.frayground.domain.game.GameState;
import java.util.List;
import java.util.UUID;

public record ConnectFourState(List<List<String>> board, List<UUID> participantOrder,
    UUID currentParticipantId, int moveCount, List<Cell> winningCells, GameResult result) implements GameState {
  public ConnectFourState {
    try {
      board = board.stream().map(List::copyOf).toList();
      participantOrder = List.copyOf(participantOrder);
      winningCells = List.copyOf(winningCells);
      ConnectFourRules.check(board, participantOrder, currentParticipantId, moveCount, winningCells, result);
    } catch (NullPointerException e) {
      throw new IllegalArgumentException("state", e);
    }
  }
}
