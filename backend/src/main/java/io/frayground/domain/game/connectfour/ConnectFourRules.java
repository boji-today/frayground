package io.frayground.domain.game.connectfour;
import io.frayground.domain.game.GameOutcome;
import io.frayground.domain.game.GameResult;
import io.frayground.domain.game.ParticipantStanding;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
final class ConnectFourRules {
  private static final int[][] DIRECTIONS = {{0, 1}, {1, 0}, {1, 1}, {1, -1}};
  private ConnectFourRules() {}
  static List<Cell> wins(List<List<String>> board, String seat) {
    Set<Cell> cells = new TreeSet<>(Comparator.comparingInt(Cell::row).thenComparingInt(Cell::column));
    for (int r = 0; r < 6; r++) {
      for (int c = 0; c < 7; c++) {
        if (!board.get(r).get(c).equals(seat)) {
          continue;
        }
        for (int[] direction : DIRECTIONS) {
          int previousRow = r - direction[0];
          int previousColumn = c - direction[1];
          if (inside(previousRow, previousColumn) && board.get(previousRow).get(previousColumn).equals(seat)) {
            continue;
          }
          List<Cell> line = new ArrayList<>();
          int y = r;
          int x = c;
          while (inside(y, x) && board.get(y).get(x).equals(seat)) {
            line.add(new Cell(y, x));
            y += direction[0];
            x += direction[1];
          }
          if (line.size() >= 4) {
            cells.addAll(line);
          }
        }
      }
    }
    return List.copyOf(cells);
  }
  static boolean inside(int row, int column) {
    return row >= 0 && row < 6 && column >= 0 && column < 7;
  }
  static boolean winsAt(List<List<String>> board, int row, int column, String seat) {
    for (int[] d : DIRECTIONS) {
      int count = 1;
      for (int sign : new int[]{-1, 1}) {
        int r = row + sign * d[0];
        int c = column + sign * d[1];
        while (inside(r, c) && board.get(r).get(c).equals(seat)) {
          count++;
          r += sign * d[0];
          c += sign * d[1];
        }
      }
      if (count >= 4) {
        return true;
      }
    }
    return false;
  }
  static GameResult ongoing() {
    return new GameResult(GameOutcome.ONGOING, List.of(), null);
  }
  static GameResult win(List<UUID> ids, int winner) {
    return new GameResult(GameOutcome.WIN, List.of(
        new ParticipantStanding(ids.get(winner), 1, null),
        new ParticipantStanding(ids.get(1 - winner), 2, null)), "FOUR_IN_A_ROW");
  }
  static GameResult draw(List<UUID> ids) {
    return new GameResult(GameOutcome.DRAW, List.of(
        new ParticipantStanding(ids.get(0), 1, null),
        new ParticipantStanding(ids.get(1), 1, null)), "BOARD_FULL");
  }
  static void check(List<List<String>> board, List<UUID> ids, UUID current, int moves,
      List<Cell> winning, GameResult result) {
    if (board.size() != 6 || ids.size() != 2 || ids.get(0).equals(ids.get(1)) || result == null) {
      throw new IllegalArgumentException("state");
    }
    int a = 0;
    int b = 0;
    for (List<String> row : board) {
      if (row.size() != 7) {
        throw new IllegalArgumentException("board");
      }
    }
    for (int c = 0; c < 7; c++) {
      boolean empty = false;
      for (int r = 5; r >= 0; r--) {
        String value = board.get(r).get(c);
        if (value.equals("EMPTY")) {
          empty = true;
        } else {
          if (empty || !(value.equals("A") || value.equals("B"))) {
            throw new IllegalArgumentException("board");
          }
          if (value.equals("A")) {
            a++;
          } else {
            b++;
          }
        }
      }
    }
    if (moves != a + b || !(a == b || a == b + 1)) {
      throw new IllegalArgumentException("moveCount");
    }
    List<Cell> aw = wins(board, "A");
    List<Cell> bw = wins(board, "B");
    if (!aw.isEmpty() && !bw.isEmpty()) {
      throw new IllegalArgumentException("winners");
    }
    GameResult expected;
    List<Cell> expectedCells;
    if (!aw.isEmpty() || !bw.isEmpty()) {
      int winner = aw.isEmpty() ? 1 : 0;
      if ((winner == 0 && a != b + 1) || (winner == 1 && a != b) || current != null) {
        throw new IllegalArgumentException("winner");
      }
      expected = win(ids, winner);
      expectedCells = winner == 0 ? aw : bw;
    } else if (moves == 42) {
      if (current != null) {
        throw new IllegalArgumentException("current");
      }
      expected = draw(ids);
      expectedCells = List.of();
    } else {
      if (!ids.get(a == b ? 0 : 1).equals(current)) {
        throw new IllegalArgumentException("current");
      }
      expected = ongoing();
      expectedCells = List.of();
    }
    if (result.status() != expected.status() || !Objects.equals(result.reason(), expected.reason())
        || result.standings().size() != expected.standings().size()
        || !new HashSet<>(result.standings()).equals(new HashSet<>(expected.standings()))
        || !winning.equals(expectedCells)) {
      throw new IllegalArgumentException("result");
    }
  }
}
