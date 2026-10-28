package io.frayground.domain.game.connectfour;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.frayground.domain.game.ActionGenerationMode;
import io.frayground.domain.game.GameOutcome;
import io.frayground.domain.game.GameResult;
import io.frayground.domain.game.Participant;
import io.frayground.domain.game.ParticipantStanding;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConnectFourBoundaryTest {
  private final UUID a = UUID.randomUUID();
  private final UUID b = UUID.randomUUID();
  private final UUID other = UUID.randomUUID();
  private final UUID agent = UUID.randomUUID();
  private final ConnectFourDefinition game = new ConnectFourDefinition();
  private final List<Participant> participants = List.of(new Participant(a, agent, "A"), new Participant(b, agent, "B"));

  private ConnectFourState play(int... columns) {
    ConnectFourState state = game.initialize(participants);
    for (int column : columns) {
      state = game.resolve(state, Map.of(state.currentParticipantId(), new PlaceDisc("place_disc", column))).newState();
    }
    return state;
  }

  private void rejected(List<List<String>> board, List<UUID> ids, UUID current, int count,
      List<Cell> cells, GameResult result) {
    assertThatThrownBy(() -> new ConnectFourState(board, ids, current, count, cells, result))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void stateFieldsAndCountsAreValidated() {
    var s = play();
    rejected(null, s.participantOrder(), a, 0, List.of(), s.result());
    rejected(s.board(), null, a, 0, List.of(), s.result());
    rejected(s.board(), s.participantOrder(), a, 0, null, s.result());
    rejected(s.board(), s.participantOrder(), a, 0, List.of(), null);
    rejected(s.board(), Arrays.asList(a, null), a, 0, List.of(), s.result());
    rejected(s.board(), List.of(a), a, 0, List.of(), s.result());
    rejected(s.board(), List.of(a, b, other), a, 0, List.of(), s.result());
    rejected(s.board(), List.of(a, a), a, 0, List.of(), s.result());
    rejected(s.board(), s.participantOrder(), other, 0, List.of(), s.result());
    rejected(s.board(), s.participantOrder(), a, -1, List.of(), s.result());
    rejected(s.board(), s.participantOrder(), a, 43, List.of(), s.result());
    var malformed = new ArrayList<>(s.board());
    malformed.set(0, List.of("EMPTY"));
    rejected(malformed, s.participantOrder(), a, 0, List.of(), s.result());
    var invalidCount = mutableBoard(s.board());
    invalidCount.get(5).set(0, "A"); invalidCount.get(5).set(1, "A");
    rejected(invalidCount, s.participantOrder(), b, 2, List.of(), s.result());
    invalidCount.get(5).set(0, "B"); invalidCount.get(5).set(1, "EMPTY");
    rejected(invalidCount, s.participantOrder(), a, 1, List.of(), s.result());
    rejected(s.board(), s.participantOrder(), a, 0, List.of(),
        new GameResult(GameOutcome.ONGOING, List.of(new ParticipantStanding(a, 1, null)), null));
    rejected(s.board(), s.participantOrder(), a, 0, List.of(),
        new GameResult(GameOutcome.ONGOING, List.of(), "unexpected"));
  }

  private List<List<String>> mutableBoard(List<List<String>> board) {
    List<List<String>> copy = new ArrayList<>();
    for (var row : board) copy.add(new ArrayList<>(row));
    return copy;
  }

  @Test
  void terminalResultsAndWinningCellsAreValidated() {
    var win = play(0, 6, 1, 6, 2, 5, 3);
    var draw = play(1,5,6,2,3,4,5,0,3,3,4,5,4,3,1,6,1,3,4,5,0,6,0,2,0,0,6,6,0,2,2,4,3,1,5,2,4,2,1,6,5,1);
    rejected(draw.board(), draw.participantOrder(), a, 42, List.of(), draw.result());
    var winCells = win.winningCells();
    rejected(win.board(), win.participantOrder(), null, win.moveCount(), winCells,
        new GameResult(GameOutcome.WIN, win.result().standings(), "wrong"));
    rejected(draw.board(), draw.participantOrder(), null, 42, List.of(),
        new GameResult(GameOutcome.DRAW, draw.result().standings(), "wrong"));
    rejected(draw.board(), draw.participantOrder(), null, 42, List.of(),
        new GameResult(GameOutcome.DRAW, List.of(new ParticipantStanding(a, 1, null), new ParticipantStanding(b, 2, null)), "BOARD_FULL"));
    rejected(draw.board(), draw.participantOrder(), null, 42, List.of(),
        new GameResult(GameOutcome.DRAW, List.of(new ParticipantStanding(a, 1, 1.0), new ParticipantStanding(b, 1, null)), "BOARD_FULL"));
    rejected(win.board(), win.participantOrder(), null, win.moveCount(), winCells,
        new GameResult(GameOutcome.WIN, List.of(new ParticipantStanding(a, 1, 1.0), new ParticipantStanding(b, 2, null)), "FOUR_IN_A_ROW"));
    var extra = new ArrayList<>(winCells); extra.add(new Cell(4, 3));
    rejected(win.board(), win.participantOrder(), null, win.moveCount(), extra, win.result());
    var duplicate = new ArrayList<>(winCells); duplicate.add(winCells.get(0));
    rejected(win.board(), win.participantOrder(), null, win.moveCount(), duplicate, win.result());
    var reversed = new ArrayList<>(winCells); java.util.Collections.reverse(reversed);
    rejected(win.board(), win.participantOrder(), null, win.moveCount(), reversed, win.result());
    rejected(win.board(), win.participantOrder(), b, win.moveCount(), winCells,
        new GameResult(GameOutcome.ONGOING, List.of(), null));
    var wrongCount = mutableBoard(win.board());
    wrongCount.get(5).set(4, "B");
    rejected(wrongCount, win.participantOrder(), null, 8, winCells, win.result());
  }

  @Test
  void copiesAndObservationsExposeImmutableCollections() {
    var win = play(0, 6, 1, 6, 2, 5, 3);
    var ids = new ArrayList<>(win.participantOrder());
    var cells = new ArrayList<>(win.winningCells());
    var board = mutableBoard(win.board());
    var copy = new ConnectFourState(board, ids, null, win.moveCount(), cells, win.result());
    board.get(5).set(0, "EMPTY"); ids.clear(); cells.clear();
    assertThat(copy).isEqualTo(win);
    assertThatThrownBy(() -> copy.board().clear()).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> copy.board().get(0).clear()).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> copy.participantOrder().clear()).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> copy.winningCells().clear()).isInstanceOf(UnsupportedOperationException.class);
    var initial = play();
    var obsA = game.observe(initial, a); var obsB = game.observe(initial, b);
    assertThat(obsA.yourSeat()).isEqualTo("A"); assertThat(obsB.yourSeat()).isEqualTo("B");
    assertThat(obsA.currentSeat()).isEqualTo("A"); assertThat(obsB.currentSeat()).isEqualTo("A");
    assertThat(obsA.board()).isEqualTo(initial.board()); assertThat(obsB.board()).isEqualTo(initial.board());
    assertThat(obsA.legalColumns()).containsExactly(0, 1, 2, 3, 4, 5, 6);
    assertThat(obsB.legalColumns()).isEmpty();
    var afterMove = play(6);
    assertThat(game.observe(afterMove, a).currentSeat()).isEqualTo("B");
    assertThat(game.observe(afterMove, a).legalColumns()).isEmpty();
    assertThat(game.observe(afterMove, b).legalColumns()).containsExactly(0, 1, 2, 3, 4, 5, 6);
    assertThatThrownBy(() -> obsA.board().clear()).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> obsA.board().get(0).clear()).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> obsA.legalColumns().clear()).isInstanceOf(UnsupportedOperationException.class);
    var actions = game.actions(initial, a);
    assertThat(actions.mode()).isEqualTo(ActionGenerationMode.ACTION_SELECTION);
    assertThat(actions.actionSchemaId()).isEqualTo("connect-four/place-disc/1");
    assertThat(actions.constraints()).isEmpty();
    assertThatThrownBy(() -> actions.constraints().put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> actions.options().clear()).isInstanceOf(UnsupportedOperationException.class);
    for (int i = 0; i < 7; i++) {
      assertThat(actions.options().get(i).selectedActionId()).isEqualTo("c" + i);
      assertThat(actions.options().get(i).action()).isEqualTo(new PlaceDisc("place_disc", i));
    }
    for (UUID id : Arrays.asList(null, other)) {
      assertThatThrownBy(() -> game.observe(initial, id)).isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> game.actions(initial, id)).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void stackingAndRejectedResolutionsLeaveOldStateUnchanged() {
    ConnectFourState state = play();
    for (int i = 0; i < 6; i++) {
      var old = state;
      var resolution = game.resolve(old, Map.of(old.currentParticipantId(), new PlaceDisc("place_disc", 0)));
      assertThat(resolution.events()).hasSize(1);
      assertThat(resolution.events().get(0).payload()).containsEntry("row", 5 - i);
      assertThat(old.board().get(5 - i).get(0)).isEqualTo("EMPTY");
      state = resolution.newState();
      assertThat(game.nextTurn(state, participants).requiredParticipants()).containsExactly(state.currentParticipantId());
    }
    var full = state;
    assertThat(game.observe(full, a).legalColumns()).containsExactly(1, 2, 3, 4, 5, 6);
    assertThat(game.actions(full, a).options()).hasSize(6);
    var originalBoard = mutableBoard(full.board());
    var originalResult = full.result();
    var originalCurrent = full.currentParticipantId();
    var originalCount = full.moveCount();
    List<Map<UUID, PlaceDisc>> invalid = new ArrayList<>();
    var nullKey = new HashMap<UUID, PlaceDisc>(); nullKey.put(null, new PlaceDisc("place_disc", 1)); invalid.add(nullKey);
    var nullValue = new HashMap<UUID, PlaceDisc>(); nullValue.put(a, null); invalid.add(nullValue);
    invalid.add(Map.of(other, new PlaceDisc("place_disc", 1)));
    invalid.add(Map.of(b, new PlaceDisc("place_disc", 1)));
    invalid.add(Map.of(a, new PlaceDisc("place_disc", 0)));
    invalid.add(Map.of(a, new PlaceDisc("place_disc", 7)));
    for (var actions : invalid) {
      assertThatThrownBy(() -> game.resolve(full, actions)).isInstanceOf(IllegalArgumentException.class);
      assertThat(full.board()).isEqualTo(originalBoard);
      assertThat(full.currentParticipantId()).isEqualTo(originalCurrent);
      assertThat(full.moveCount()).isEqualTo(originalCount);
      assertThat(game.result(full)).isEqualTo(originalResult);
    }
  }
}
