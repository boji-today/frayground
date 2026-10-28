package io.frayground.domain.game.connectfour;

import io.frayground.domain.game.ActionGenerationMode;
import io.frayground.domain.game.ActionOption;
import io.frayground.domain.game.ActionSpecification;
import io.frayground.domain.game.GameDefinition;
import io.frayground.domain.game.GameEvent;
import io.frayground.domain.game.GameEventDraft;
import io.frayground.domain.game.GameKey;
import io.frayground.domain.game.GameOutcome;
import io.frayground.domain.game.GameResult;
import io.frayground.domain.game.Participant;
import io.frayground.domain.game.Resolution;
import io.frayground.domain.game.SchedulingMode;
import io.frayground.domain.game.TurnPlan;
import io.frayground.domain.game.ValidationResult;
import io.frayground.domain.game.VisibleGameEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ConnectFourDefinition implements GameDefinition<ConnectFourState, PlaceDisc, ConnectFourObservation> {
  public static final String SCHEMA_ID = "connect-four/place-disc/1";
  @Override
  public GameKey key() {
    return new GameKey("connect-four", "1");
  }
  private List<UUID> order(List<Participant> participants) {
    if (participants == null || participants.size() != 2) {
      throw new IllegalArgumentException("participants");
    }
    Map<String, UUID> seats = new HashMap<>();
    for (Participant participant : participants) {
      if (participant == null || seats.putIfAbsent(participant.seatKey(), participant.participantId()) != null) {
        throw new IllegalArgumentException("participants");
      }
    }
    if (!seats.keySet().equals(Set.of("A", "B")) || seats.get("A").equals(seats.get("B"))) {
      throw new IllegalArgumentException("participants");
    }
    return List.of(seats.get("A"), seats.get("B"));
  }
  @Override
  public ConnectFourState initialize(List<Participant> participants) {
    List<UUID> ids = order(participants);
    return new ConnectFourState(Collections.nCopies(6, Collections.nCopies(7, "EMPTY")),
        ids, ids.get(0), 0, List.of(), ConnectFourRules.ongoing());
  }
  @Override
  public TurnPlan nextTurn(ConnectFourState state, List<Participant> participants) {
    if (!state.participantOrder().equals(order(participants))) {
      throw new IllegalArgumentException("participants");
    }
    return new TurnPlan(SchedulingMode.TURN_BASED_SEQUENTIAL,
        state.currentParticipantId() == null ? List.of() : List.of(state.currentParticipantId()));
  }
  private int seat(ConnectFourState state, UUID id) {
    return id == null ? -1 : state.participantOrder().indexOf(id);
  }
  @Override
  public ConnectFourObservation observe(ConnectFourState state, UUID id) {
    int index = seat(state, id);
    if (index < 0) {
      throw new IllegalArgumentException("participant");
    }
    List<Integer> columns = new ArrayList<>();
    if (id.equals(state.currentParticipantId())) {
      for (int column = 0; column < 7; column++) {
        if (state.board().get(0).get(column).equals("EMPTY")) {
          columns.add(column);
        }
      }
    }
    String current = state.currentParticipantId() == null ? null
        : (seat(state, state.currentParticipantId()) == 0 ? "A" : "B");
    return new ConnectFourObservation(state.board(), index == 0 ? "A" : "B",
        current, columns, state.moveCount());
  }
  @Override
  public ActionSpecification<PlaceDisc> actions(ConnectFourState state, UUID id) {
    List<ActionOption<PlaceDisc>> options = observe(state, id).legalColumns().stream()
        .map(column -> new ActionOption<>("c" + column, new PlaceDisc("place_disc", column)))
        .toList();
    return new ActionSpecification<>(ActionGenerationMode.ACTION_SELECTION, SCHEMA_ID, options, Map.of());
  }
  @Override
  public ValidationResult validate(ConnectFourState state, UUID id, PlaceDisc action) {
    String code = null;
    if (seat(state, id) < 0) {
      code = "UNKNOWN_PARTICIPANT";
    } else if (state.currentParticipantId() == null) {
      code = "GAME_FINISHED";
    } else if (!id.equals(state.currentParticipantId())) {
      code = "NOT_YOUR_TURN";
    } else if (action == null || !"place_disc".equals(action.type())) {
      code = "INVALID_ACTION";
    } else if (action.column() < 0 || action.column() > 6) {
      code = "COLUMN_OUT_OF_RANGE";
    } else if (!state.board().get(0).get(action.column()).equals("EMPTY")) {
      code = "COLUMN_FULL";
    }
    return code == null ? new ValidationResult.Accepted() : new ValidationResult.Rejected(code);
  }
  @Override
  public Resolution<ConnectFourState> resolve(ConnectFourState state, Map<UUID, PlaceDisc> actions) {
    if (actions == null || actions.size() != 1) {
      throw new IllegalArgumentException("actions");
    }
    var entry = actions.entrySet().iterator().next();
    UUID id = entry.getKey();
    PlaceDisc action = entry.getValue();
    if (!(validate(state, id, action) instanceof ValidationResult.Accepted)) {
      throw new IllegalArgumentException("action");
    }
    int column = action.column();
    int row = 5;
    while (!state.board().get(row).get(column).equals("EMPTY")) {
      row--;
    }
    List<List<String>> board = new ArrayList<>();
    for (List<String> old : state.board()) {
      board.add(new ArrayList<>(old));
    }
    int index = seat(state, id);
    String token = index == 0 ? "A" : "B";
    board.get(row).set(column, token);
    List<Cell> cells = ConnectFourRules.winsAt(board, row, column, token)
        ? ConnectFourRules.wins(board, token) : List.of();
    int count = state.moveCount() + 1;
    GameResult result = !cells.isEmpty() ? ConnectFourRules.win(state.participantOrder(), index)
        : count == 42 ? ConnectFourRules.draw(state.participantOrder()) : ConnectFourRules.ongoing();
    UUID next = result.status() == GameOutcome.ONGOING ? state.participantOrder().get(1 - index) : null;
    ConnectFourState updated = new ConnectFourState(board, state.participantOrder(), next, count, cells, result);
    List<GameEventDraft> events = new ArrayList<>();
    events.add(new GameEventDraft("DiscPlaced", 1,
        Map.of("row", row, "column", column, "seat", token, "participantId", id)));
    if (next == null) {
      events.add(new GameEventDraft("MatchFinished", 1, Map.of("result", result, "winningCells", cells)));
    }
    return new Resolution<>(updated, events, result);
  }
  @Override
  public GameResult result(ConnectFourState state) {
    return state.result();
  }
  @Override
  public List<VisibleGameEvent> visibleHistory(UUID id, List<GameEvent> events) {
    if (id == null || events == null) {
      throw new IllegalArgumentException("history");
    }
    List<VisibleGameEvent> visible = new ArrayList<>();
    for (GameEvent event : events) {
      List<String> keys = switch (event.type()) {
        case "DiscPlaced" -> List.of("row", "column", "seat", "participantId");
        case "MatchFinished" -> List.of("result", "winningCells");
        default -> List.of();
      };
      if (keys.isEmpty()) {
        continue;
      }
      Map<String, Object> payload = new HashMap<>();
      for (String key : keys) {
        if (event.payload().containsKey(key)) {
          payload.put(key, copy(event.payload().get(key)));
        }
      }
      visible.add(new VisibleGameEvent(event.type(), payload));
    }
    return List.copyOf(visible);
  }
  private Object copy(Object value) {
    if (value instanceof List<?> list) {
      return list.stream().map(this::copy).toList();
    }
    if (value instanceof Map<?, ?> map) {
      Map<Object, Object> result = new HashMap<>();
      map.forEach((key, nested) -> result.put(key, copy(nested)));
      // JSON event payloads may contain null scores and reasons.
      return Collections.unmodifiableMap(result);
    }
    return value;
  }
}
