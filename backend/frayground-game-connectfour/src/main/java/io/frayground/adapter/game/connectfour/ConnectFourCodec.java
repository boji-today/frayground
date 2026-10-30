package io.frayground.adapter.game.connectfour;

import io.frayground.adapter.game.GameCodec;
import io.frayground.domain.game.GameOutcome;
import io.frayground.domain.game.GameResult;
import io.frayground.domain.game.ParticipantStanding;
import io.frayground.domain.game.connectfour.Cell;
import io.frayground.domain.game.connectfour.ConnectFourObservation;
import io.frayground.domain.game.connectfour.ConnectFourState;
import io.frayground.domain.game.connectfour.PlaceDisc;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class ConnectFourCodec implements GameCodec<ConnectFourState, PlaceDisc, ConnectFourObservation> {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Override
  public JsonNode encodeState(ConnectFourState state) {
    return JSON.valueToTree(state);
  }

  @Override
  public JsonNode encodeObservation(ConnectFourObservation observation) {
    return JSON.valueToTree(observation);
  }

  @Override
  public JsonNode encodeAction(PlaceDisc action) {
    if (action == null || !"place_disc".equals(action.type()) || action.column() < 0 || action.column() > 6) {
      throw new IllegalArgumentException("action");
    }
    return JSON.valueToTree(action);
  }

  @Override
  public PlaceDisc decodeAction(JsonNode data) {
    fields(data, "type", "column");
    String type = string(data.get("type"));
    int column = integer(data.get("column"));
    if (!type.equals("place_disc") || column < 0 || column > 6) {
      throw new IllegalArgumentException("action");
    }
    return new PlaceDisc(type, column);
  }

  @Override
  public ConnectFourState decodeState(JsonNode data) {
    try {
      fields(data, "board", "participantOrder", "currentParticipantId", "moveCount", "winningCells", "result");
      List<List<String>> board = new ArrayList<>();
      for (JsonNode row : array(data.get("board"))) {
        List<String> values = new ArrayList<>();
        for (JsonNode cell : array(row)) {
          values.add(string(cell));
        }
        board.add(values);
      }
      List<UUID> ids = new ArrayList<>();
      for (JsonNode id : array(data.get("participantOrder"))) {
        ids.add(uuid(id));
      }
      JsonNode current = data.get("currentParticipantId");
      UUID currentId = current.isNull() ? null : uuid(current);
      List<Cell> cells = new ArrayList<>();
      for (JsonNode cell : array(data.get("winningCells"))) {
        fields(cell, "row", "column");
        cells.add(new Cell(integer(cell.get("row")), integer(cell.get("column"))));
      }
      JsonNode result = data.get("result");
      fields(result, "status", "standings", "reason");
      GameOutcome outcome = GameOutcome.valueOf(string(result.get("status")));
      List<ParticipantStanding> standings = new ArrayList<>();
      for (JsonNode standing : array(result.get("standings"))) {
        fields(standing, "participantId", "rank", "score");
        JsonNode score = standing.get("score");
        if (!score.isNull() && !score.isNumber()) {
          throw new IllegalArgumentException("score");
        }
        standings.add(new ParticipantStanding(uuid(standing.get("participantId")),
            integer(standing.get("rank")), score.isNull() ? null : score.doubleValue()));
      }
      JsonNode reason = result.get("reason");
      return new ConnectFourState(board, ids, currentId, integer(data.get("moveCount")), cells,
          new GameResult(outcome, standings, reason.isNull() ? null : string(reason)));
    } catch (RuntimeException e) {
      if (e instanceof IllegalArgumentException iae) {
        throw iae;
      }
      throw new IllegalArgumentException("state", e);
    }
  }
  private static void fields(JsonNode node, String... names) {
    if (node == null || !node.isObject() || node.size() != names.length) {
      throw new IllegalArgumentException("fields");
    }
    for (String name : names) {
      if (!node.has(name)) {
        throw new IllegalArgumentException("fields");
      }
    }
  }
  private static JsonNode array(JsonNode node) {
    if (node == null || !node.isArray()) {
      throw new IllegalArgumentException("array");
    }
    return node;
  }
  private static String string(JsonNode node) {
    if (node == null || !node.isString()) {
      throw new IllegalArgumentException("string");
    }
    return node.stringValue();
  }
  private static int integer(JsonNode node) {
    if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
      throw new IllegalArgumentException("integer");
    }
    return node.intValue();
  }
  private static UUID uuid(JsonNode node) {
    String value = string(node);
    UUID result = UUID.fromString(value);
    if (!result.toString().equalsIgnoreCase(value)) {
      throw new IllegalArgumentException("uuid");
    }
    return result;
  }
}
