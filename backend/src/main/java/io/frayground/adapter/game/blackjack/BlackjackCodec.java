package io.frayground.adapter.game.blackjack;

import io.frayground.adapter.game.GameCodec;
import io.frayground.domain.game.GameOutcome;
import io.frayground.domain.game.GameResult;
import io.frayground.domain.game.ParticipantStanding;
import io.frayground.domain.game.blackjack.BlackjackAction;
import io.frayground.domain.game.blackjack.BlackjackObservation;
import io.frayground.domain.game.blackjack.BlackjackPhase;
import io.frayground.domain.game.blackjack.BlackjackState;
import io.frayground.domain.game.blackjack.Card;
import io.frayground.domain.game.blackjack.PlayerSeat;
import io.frayground.domain.game.blackjack.PlayerStatus;
import io.frayground.domain.game.blackjack.PublicAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

public final class BlackjackCodec implements GameCodec<BlackjackState, BlackjackAction, BlackjackObservation> {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Set<String> STATE_FIELDS = Set.of("phase", "participantOrder", "seats",
      "currentParticipantId", "seed", "shuffleAlgorithm", "shoe", "cursor", "players", "dealerCards",
      "holeHidden", "actions", "result");

  @Override
  public JsonNode encodeState(BlackjackState state) {
    return stripNulls(JSON.valueToTree(state));
  }

  @Override
  public JsonNode encodeObservation(BlackjackObservation observation) {
    return JSON.valueToTree(observation);
  }

  @Override
  public JsonNode encodeAction(BlackjackAction action) {
    if (action == null || (!action.type().equals("hit") && !action.type().equals("stand"))) {
      throw new IllegalArgumentException("action");
    }
    return JSON.valueToTree(action);
  }

  @Override
  public BlackjackAction decodeAction(JsonNode data) {
    fields(data, "type");
    String type = string(data.get("type"));
    if (!type.equals("hit") && !type.equals("stand")) {
      throw new IllegalArgumentException("action");
    }
    return new BlackjackAction(type);
  }

  @Override
  public BlackjackState decodeState(JsonNode data) {
    try {
      objectFields(data, STATE_FIELDS, "phase", "participantOrder", "seats", "seed", "shuffleAlgorithm",
          "shoe", "cursor", "players", "dealerCards", "holeHidden", "actions", "result");
      List<UUID> ids = new ArrayList<>();
      for (JsonNode id : array(data.get("participantOrder"))) {
        ids.add(uuid(id));
      }
      List<String> seats = new ArrayList<>();
      for (JsonNode seat : array(data.get("seats"))) {
        seats.add(string(seat));
      }
      JsonNode current = data.get("currentParticipantId");
      UUID currentId = current == null || current.isNull() ? null : uuid(current);
      List<PlayerSeat> players = new ArrayList<>();
      for (JsonNode player : array(data.get("players"))) {
        fields(player, "participantId", "seat", "cards", "status");
        players.add(new PlayerSeat(uuid(player.get("participantId")), string(player.get("seat")),
            cards(player.get("cards")), PlayerStatus.valueOf(string(player.get("status")))));
      }
      List<PublicAction> actions = new ArrayList<>();
      for (JsonNode action : array(data.get("actions"))) {
        fields(action, "type", "seat");
        actions.add(new PublicAction(string(action.get("type")), string(action.get("seat"))));
      }
      JsonNode result = data.get("result");
      fields(result, "status", "standings", "reason");
      List<ParticipantStanding> standings = new ArrayList<>();
      for (JsonNode standing : array(result.get("standings"))) {
        fields(standing, "participantId", "rank", "score");
        JsonNode score = standing.get("score");
        if (!score.isNumber()) {
          throw new IllegalArgumentException("score");
        }
        standings.add(new ParticipantStanding(uuid(standing.get("participantId")),
            integer(standing.get("rank")), score.doubleValue()));
      }
      return new BlackjackState(BlackjackPhase.valueOf(string(data.get("phase"))), ids, seats, currentId,
          longValue(data.get("seed")), string(data.get("shuffleAlgorithm")), cards(data.get("shoe")),
          integer(data.get("cursor")), players, cards(data.get("dealerCards")), bool(data.get("holeHidden")),
          actions, new GameResult(GameOutcome.valueOf(string(result.get("status"))), standings,
              string(result.get("reason"))));
    } catch (RuntimeException exception) {
      if (exception instanceof IllegalArgumentException illegal) {
        throw illegal;
      }
      throw new IllegalArgumentException("state", exception);
    }
  }

  private static List<Card> cards(JsonNode node) {
    List<Card> cards = new ArrayList<>();
    for (JsonNode card : array(node)) {
      fields(card, "deckNo", "suit", "rank");
      cards.add(new Card(integer(card.get("deckNo")), string(card.get("suit")), string(card.get("rank"))));
    }
    return cards;
  }

  private static void objectFields(JsonNode node, Set<String> allowed, String... required) {
    if (node == null || !node.isObject()) {
      throw new IllegalArgumentException("fields");
    }
    node.propertyNames().forEach(name -> {
      if (!allowed.contains(name)) {
        throw new IllegalArgumentException("fields");
      }
    });
    for (String name : required) {
      if (!node.has(name)) {
        throw new IllegalArgumentException("fields");
      }
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

  private static JsonNode stripNulls(JsonNode node) {
    if (node instanceof ObjectNode object) {
      List<String> remove = new ArrayList<>();
      object.propertyNames().forEach(name -> {
        JsonNode child = object.get(name);
        if (child == null || child.isNull()) {
          remove.add(name);
        } else {
          stripNulls(child);
        }
      });
      remove.forEach(object::remove);
    } else if (node != null && node.isArray()) {
      node.forEach(BlackjackCodec::stripNulls);
    }
    return node;
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

  private static long longValue(JsonNode node) {
    if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
      throw new IllegalArgumentException("long");
    }
    return node.longValue();
  }

  private static boolean bool(JsonNode node) {
    if (node == null || !node.isBoolean()) {
      throw new IllegalArgumentException("boolean");
    }
    return node.booleanValue();
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
