package io.frayground.domain.game.connectfour;

import io.frayground.domain.game.GameObservation;
import java.util.List;

public record ConnectFourObservation(List<List<String>> board, String yourSeat, String currentSeat,
    List<Integer> legalColumns, int moveCount) implements GameObservation {
  public ConnectFourObservation {
    board = board.stream().map(List::copyOf).toList();
    legalColumns = List.copyOf(legalColumns);
  }
}
