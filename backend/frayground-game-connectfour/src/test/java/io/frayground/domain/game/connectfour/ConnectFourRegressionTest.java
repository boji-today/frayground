package io.frayground.domain.game.connectfour;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.frayground.adapter.game.connectfour.ConnectFourCodec;
import io.frayground.domain.game.GameEvent;
import io.frayground.domain.game.GameEventDraft;
import io.frayground.domain.game.GameOutcome;
import io.frayground.domain.game.Participant;
import io.frayground.domain.game.Resolution;
import io.frayground.domain.game.VisibleGameEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ConnectFourRegressionTest {
  private static final int[] DRAW = {1,5,6,2,3,4,5,0,3,3,4,5,4,3,1,6,1,3,4,5,0,6,0,2,0,0,6,6,0,2,2,4,3,1,5,2,4,2,1,6,5,1};
  private static final int[] WIN = {0,6,3,5,6,3,1,5,0,3,4,3,4,6,4,2,5,1,5,5,0,0,0,0,5,1,2,4,2,6,6,2,6,2,4,4,1,2,3,3,1,1};
  private final UUID a = UUID.randomUUID();
  private final UUID b = UUID.randomUUID();
  private final UUID agent = UUID.randomUUID();
  private final ConnectFourDefinition game = new ConnectFourDefinition();
  private final List<Participant> participants = List.of(
      new Participant(a, agent, "A"), new Participant(b, agent, "B"));

  private Resolution<ConnectFourState> finish(int[] columns) {
    ConnectFourState state = game.initialize(participants);
    Resolution<ConnectFourState> resolution = null;
    for (int i = 0; i < columns.length; i++) {
      assertThat(state.result().status()).isEqualTo(GameOutcome.ONGOING);
      resolution = game.resolve(state, Map.of(state.currentParticipantId(),
          new PlaceDisc("place_disc", columns[i])));
      state = resolution.newState();
    }
    return resolution;
  }

  @Test
  void winsInEveryDirectionAndCollectsLongAndIntersectingLines() {
    int[][] sequences = {
        {6,2,5,1,4,4,0,1,6,2,4,4,0,5,1,3,1,2,4,6,6,3},
        {6,3,6,4,0,4,5,6,0,3,3,5,1,1,2,5,4,1,0,6},
        {3,2,6,2,6,2,0,1,6,2},
        {1,2,2,2,4,4,3,0,6,3,0,6,2,5,1,6,5,5,0,5,5,3,3,4},
        {4,0,4,5,1,3,6,2,0,2,3,1,2,0,2,6,2,6,1,3,1}
    };
    List<List<Cell>> expected = List.of(
        List.of(new Cell(4,1),new Cell(4,2),new Cell(4,3),new Cell(4,4),new Cell(4,5)),
        List.of(new Cell(2,6),new Cell(3,5),new Cell(4,4),new Cell(5,3)),
        List.of(new Cell(2,2),new Cell(3,2),new Cell(4,2),new Cell(5,2)),
        List.of(new Cell(2,5),new Cell(3,2),new Cell(3,3),new Cell(3,4),new Cell(3,5),new Cell(3,6),new Cell(4,3),new Cell(5,2)),
        List.of(new Cell(2,1),new Cell(3,2),new Cell(4,3),new Cell(5,4)));
    for (int i = 0; i < sequences.length; i++) {
      var state = finish(sequences[i]).newState();
      assertThat(state.result().status()).isEqualTo(GameOutcome.WIN);
      assertThat(state.winningCells()).isEqualTo(expected.get(i));
    }
  }

  @Test
  void separatedRowsDoNotFormAHorizontalLine() {
    var state = finish(new int[]{6, 0, 0, 1, 1, 2, 2}).newState();
    assertThat(state.result().status()).isEqualTo(GameOutcome.ONGOING);
    assertThat(state.winningCells()).isEmpty();
  }

  @Test
  void lastMoveDrawAndWinTakePriorityAndRoundTrip() {
    var codec = new ConnectFourCodec();
    for (int[] sequence : List.of(DRAW, WIN)) {
      var resolution = finish(sequence);
      var state = resolution.newState();
      assertThat(state.moveCount()).isEqualTo(42);
      assertThat(state.result().status()).isEqualTo(sequence == DRAW ? GameOutcome.DRAW : GameOutcome.WIN);
      assertThat(codec.decodeState(codec.encodeState(state))).isEqualTo(state);
      assertThat(game.observe(state, a).currentSeat()).isNull();
      assertThat(game.observe(state, b).legalColumns()).isEmpty();
      assertThat(game.nextTurn(state, participants).requiredParticipants()).isEmpty();
      assertThat(game.nextTurn(state, participants).mode())
          .isEqualTo(io.frayground.domain.game.SchedulingMode.TURN_BASED_SEQUENTIAL);
      assertThat(game.actions(state, a).options()).isEmpty();
      assertThat(game.actions(state, b).options()).isEmpty();
      assertThat(game.observe(state, a).yourSeat()).isEqualTo("A");
      assertThat(game.observe(state, b).yourSeat()).isEqualTo("B");
      assertThat(game.observe(state, b).currentSeat()).isNull();
      assertThat(game.observe(state, a).legalColumns()).isEmpty();
      assertThat(game.observe(state, a).board()).isEqualTo(state.board());
      assertThat(game.observe(state, b).board()).isEqualTo(state.board());
      assertThat(resolution.events()).extracting(GameEventDraft::type).containsExactly("DiscPlaced", "MatchFinished");
      assertThat(resolution.events()).allSatisfy(event -> assertThat(event.eventVersion()).isEqualTo(1));
      var placed = resolution.events().get(0);
      assertThat(placed.payload()).containsOnlyKeys("row", "column", "seat", "participantId")
          .containsEntry("row", 0).containsEntry("column", sequence[41])
          .containsEntry("seat", "B").containsEntry("participantId", b);
      assertThat(resolution.events().get(1).payload()).containsEntry("result", state.result())
          .containsEntry("winningCells", state.winningCells());
      var history = new java.util.ArrayList<GameEvent>();
      for (int index = 0; index < resolution.events().size(); index++) {
        var event = resolution.events().get(index);
        history.add(new GameEvent(UUID.randomUUID(), index + 1, 42, null, event.type(),
            event.eventVersion(), event.payload(), Instant.EPOCH));
      }
      assertThat(game.visibleHistory(a, history)).extracting(VisibleGameEvent::type)
          .containsExactly("DiscPlaced", "MatchFinished");
      assertThatThrownBy(() -> game.resolve(state, Map.of(a, new PlaceDisc("place_disc", 0))))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void reconstructedTerminalEventsPreserveNullAndAreDeeplyImmutable() {
    JsonMapper mapper = JsonMapper.builder().build();
    for (int[] sequence : List.of(DRAW, WIN)) {
      GameEventDraft draft = finish(sequence).events().get(1);
      Map<String, Object> reconstructed = mapper.readValue(mapper.writeValueAsString(draft.payload()), Map.class);
      GameEvent event = new GameEvent(UUID.randomUUID(), 1, 42, null,
          draft.type(), 1, reconstructed, Instant.EPOCH);
      VisibleGameEvent visible = game.visibleHistory(a, List.of(event)).get(0);
      Map<String, Object> result = (Map<String, Object>) visible.payload().get("result");
      List<Map<String, Object>> standings = (List<Map<String, Object>>) result.get("standings");
      assertThat(result.get("status")).isEqualTo(sequence == DRAW ? "DRAW" : "WIN");
      assertThat(standings).hasSize(2);
      assertThat(standings.get(0)).containsEntry("score", null);
      assertThatThrownBy(() -> standings.get(0).put("score", 2)).isInstanceOf(UnsupportedOperationException.class);
      assertThatThrownBy(() -> standings.clear()).isInstanceOf(UnsupportedOperationException.class);
      ((List<Map<String, Object>>) ((Map<String, Object>) reconstructed.get("result")).get("standings"))
          .get(0).put("score", 5);
      assertThat(standings.get(0)).containsEntry("score", null);
      assertThat(game.visibleHistory(a, List.of(new GameEvent(UUID.randomUUID(), 1, 42, null,
          draft.type(), 1, draft.payload(), Instant.EPOCH))).get(0).payload().get("result"))
          .isEqualTo(draft.payload().get("result"));
    }
  }

  @Test
  void historyProjectsKnownFieldsOnly() {
    var payload = new java.util.HashMap<String, Object>();
    payload.put("row", 5); payload.put("column", 0); payload.put("seat", "A");
    payload.put("participantId", a); payload.put("secret", "hidden");
    var known = new GameEvent(UUID.randomUUID(), 1, 1, null, "DiscPlaced", 1, payload, Instant.EPOCH);
    var unknown = new GameEvent(UUID.randomUUID(), 2, 1, null, "Private", 1, Map.of("secret", "hidden"), Instant.EPOCH);
    var visible = game.visibleHistory(a, List.of(known, unknown));
    assertThat(visible).hasSize(1);
    assertThat(visible.get(0).payload()).containsOnlyKeys("row", "column", "seat", "participantId");
    assertThatThrownBy(() -> visible.add(visible.get(0))).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> game.visibleHistory(null, List.of(known))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> game.visibleHistory(a, null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void terminalSnapshotRejectsWrongResultAndWinningCells() {
    var state = finish(WIN).newState();
    var result = state.result();
    assertThatThrownBy(() -> new ConnectFourState(state.board(), state.participantOrder(), a,
        state.moveCount(), state.winningCells(), result)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ConnectFourState(state.board(), state.participantOrder(), null,
        state.moveCount(), List.of(), result)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ConnectFourState(state.board(), state.participantOrder(), null,
        state.moveCount(), state.winningCells(),
        new io.frayground.domain.game.GameResult(GameOutcome.ONGOING, List.of(), null)))
        .isInstanceOf(IllegalArgumentException.class);
    var wrongStandings = new io.frayground.domain.game.GameResult(GameOutcome.WIN,
        List.of(new io.frayground.domain.game.ParticipantStanding(a, 1, null),
            new io.frayground.domain.game.ParticipantStanding(b, 1, null)), "FOUR_IN_A_ROW");
    assertThatThrownBy(() -> new ConnectFourState(state.board(), state.participantOrder(), null,
        state.moveCount(), state.winningCells(), wrongStandings)).isInstanceOf(IllegalArgumentException.class);
  }
}
