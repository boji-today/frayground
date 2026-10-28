package io.frayground.domain.game.connectfour;
import io.frayground.domain.game.GameOutcome;
import io.frayground.domain.game.GameResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
class ConnectFourStateTest {
  @Test void copiesAndChecksBoard() {
    var a = UUID.randomUUID();
    var b = UUID.randomUUID();
    List<List<String>> board = new ArrayList<>();
    for(int i=0;i<6;i++) board.add(new ArrayList<>(Collections.nCopies(7,"EMPTY")));
    var state=new ConnectFourState(board,List.of(a,b),a,0,List.of(),new GameResult(GameOutcome.ONGOING,List.of(),null));
    board.get(5).set(0,"A");
    assertThat(state.board().get(5).get(0)).isEqualTo("EMPTY");
    assertThatThrownBy(()->state.board().get(0).set(0,"A")).isInstanceOf(UnsupportedOperationException.class);
    board.get(5).set(0,"EMPTY");
    board.get(4).set(0,"A");
    assertThatThrownBy(()->new ConnectFourState(board,List.of(a,b),b,1,List.of(),new GameResult(GameOutcome.ONGOING,List.of(),null))).isInstanceOf(IllegalArgumentException.class);
  }

  @Test void rejectsInvalidSnapshots() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    GameResult ongoing = new GameResult(GameOutcome.ONGOING, List.of(), null);
    List<List<String>> empty = new ArrayList<>();
    for (int row = 0; row < 6; row++) empty.add(new ArrayList<>(Collections.nCopies(7, "EMPTY")));
    assertThatThrownBy(() -> new ConnectFourState(empty.subList(0, 5), List.of(a, b), a, 0, List.of(), ongoing)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ConnectFourState(empty, List.of(a, a), a, 0, List.of(), ongoing)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ConnectFourState(empty, List.of(a, b), b, 0, List.of(), ongoing)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ConnectFourState(empty, List.of(a, b), a, 1, List.of(), ongoing)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ConnectFourState(empty, List.of(a, b), a, 0, List.of(new Cell(5, 0)), ongoing)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ConnectFourState(empty, List.of(a, b), a, 0, List.of(), new GameResult(GameOutcome.DRAW, List.of(), "BOARD_FULL"))).isInstanceOf(IllegalArgumentException.class);
    empty.get(5).set(0, "X");
    assertThatThrownBy(() -> new ConnectFourState(empty, List.of(a, b), a, 0, List.of(), ongoing)).isInstanceOf(IllegalArgumentException.class);
    empty.get(5).set(0, null);
    assertThatThrownBy(() -> new ConnectFourState(empty, List.of(a, b), a, 0, List.of(), ongoing)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Cell(6, 0)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test void rejectsDualWinnersAndImpossibleCounts() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    var board = new ArrayList<List<String>>();
    for (int row = 0; row < 6; row++) {
      board.add(new ArrayList<>(Collections.nCopies(7, "EMPTY")));
    }
    var ongoing = new GameResult(GameOutcome.ONGOING, List.of(), null);
    board.get(5).set(0, "B");
    assertThatThrownBy(() -> new ConnectFourState(board, List.of(a, b), a, 1, List.of(), ongoing))
        .isInstanceOf(IllegalArgumentException.class);
    for (int column = 0; column < 4; column++) {
      board.get(5).set(column, "A");
      board.get(4).set(column, "B");
    }
    assertThatThrownBy(() -> new ConnectFourState(board, List.of(a, b), null, 8, List.of(), ongoing))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
