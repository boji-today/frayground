package io.frayground.domain.game.connectfour;

public record Cell(int row, int column) {
  public Cell {
    if (row < 0 || row >= 6 || column < 0 || column >= 7) {
      throw new IllegalArgumentException("cell");
    }
  }
}
