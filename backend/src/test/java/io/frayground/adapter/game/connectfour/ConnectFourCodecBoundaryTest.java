package io.frayground.adapter.game.connectfour;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.frayground.domain.game.Participant;
import io.frayground.domain.game.connectfour.ConnectFourDefinition;
import io.frayground.domain.game.connectfour.ConnectFourState;
import io.frayground.domain.game.connectfour.PlaceDisc;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class ConnectFourCodecBoundaryTest {
  private final JsonMapper json = JsonMapper.builder().build();
  private final ConnectFourCodec codec = new ConnectFourCodec();
  private final ConnectFourDefinition game = new ConnectFourDefinition();
  private final UUID a = UUID.randomUUID();
  private final UUID b = UUID.randomUUID();
  private final UUID agent = UUID.randomUUID();
  private final List<Participant> participants = List.of(new Participant(a, agent, "A"), new Participant(b, agent, "B"));

  private ConnectFourState play(int... columns) {
    var state = game.initialize(participants);
    for (int column : columns) {
      state = game.resolve(state, Map.of(state.currentParticipantId(), new PlaceDisc("place_disc", column))).newState();
    }
    return state;
  }

  private ObjectNode snapshot(ConnectFourState state) {
    return (ObjectNode) codec.encodeState(state);
  }

  private void rejects(JsonNode value) {
    assertThatThrownBy(() -> codec.decodeState(value)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsWrongStaticFieldTypesAndInvalidValues() {
    var initial = play();
    var win = play(0, 6, 1, 6, 2, 5, 3);
    for (JsonNode value : List.of(json.readTree("null"), json.readTree("[]"), json.readTree("\"state\""))) rejects(value);
    assertThatThrownBy(() -> codec.decodeState(null)).isInstanceOf(IllegalArgumentException.class);
    for (String value : List.of("null", "{}", "\"board\"")) {
      var tree = snapshot(initial); tree.set("board", json.readTree(value)); rejects(tree);
    }
    var badRow = snapshot(initial); ((ArrayNode) badRow.get("board")).set(0, json.readTree("{}")); rejects(badRow);
    for (String value : List.of("null", "1", "\"UNKNOWN\"")) {
      var tree = snapshot(initial); ((ArrayNode) tree.get("board").get(5)).set(0, json.readTree(value)); rejects(tree);
    }
    for (String value : List.of("null", "{}", "[]", "[\"1-1-1-1-1\",\"" + b + "\"]",
        "[\"invalid\",\"" + b + "\"]", "[1,\"" + b + "\"]",
        "[\"" + a + "\",\"" + a + "\"]", "[\"" + a + "\"]")) {
      var tree = snapshot(initial); tree.set("participantOrder", json.readTree(value)); rejects(tree);
    }
    for (String value : List.of("true", "\"invalid\"", "null")) {
      var tree = snapshot(initial); tree.set("currentParticipantId", json.readTree(value)); rejects(tree);
    }
    for (String value : List.of("\"0\"", "0.0", "true", "null", "2147483648", "1")) {
      var tree = snapshot(initial); tree.set("moveCount", json.readTree(value)); rejects(tree);
    }
    for (String value : List.of("null", "{}", "[{}]", "[{\"row\":5}]",
        "[{\"row\":5,\"column\":0,\"extra\":1}]", "[{\"row\":\"5\",\"column\":0}]",
        "[{\"row\":5,\"column\":true}]", "[{\"row\":6,\"column\":0}]",
        "[{\"row\":5,\"column\":7}]")) {
      var tree = snapshot(initial); tree.set("winningCells", json.readTree(value)); rejects(tree);
    }
    for (String value : List.of("null", "[]", "\"result\"")) {
      var tree = snapshot(initial); tree.set("result", json.readTree(value)); rejects(tree);
    }
    for (String value : List.of("null", "42", "\"UNKNOWN\"")) {
      var tree = snapshot(initial); ((ObjectNode) tree.get("result")).set("status", json.readTree(value)); rejects(tree);
    }
    for (String value : List.of("null", "{}", "[{}]")) {
      var tree = snapshot(initial); ((ObjectNode) tree.get("result")).set("standings", json.readTree(value)); rejects(tree);
    }
    for (String value : List.of("true", "42", "[]")) {
      var tree = snapshot(initial); ((ObjectNode) tree.get("result")).set("reason", json.readTree(value)); rejects(tree);
    }
    for (String value : List.of("{}", "{\"participantId\":\"" + a + "\",\"rank\":1}",
        "{\"participantId\":\"" + a + "\",\"rank\":1,\"score\":null,\"extra\":0}",
        "{\"participantId\":\"" + a + "\",\"rank\":\"1\",\"score\":null}",
        "{\"participantId\":\"" + a + "\",\"rank\":1.0,\"score\":null}",
        "{\"participantId\":\"" + a + "\",\"rank\":null,\"score\":null}",
        "{\"participantId\":\"" + a + "\",\"rank\":1,\"score\":\"1\"}",
        "{\"participantId\":\"" + a + "\",\"rank\":1,\"score\":true}",
        "{\"participantId\":\"" + UUID.randomUUID() + "\",\"rank\":1,\"score\":null}")) {
      var tree = snapshot(win); ((ArrayNode) tree.get("result").get("standings")).set(0, json.readTree(value)); rejects(tree);
    }
  }

  @Test
  void observationAndActionEncodingAreIndependentAndComplete() {
    var state = play(6);
    var observation = game.observe(state, b);
    var tree = (ObjectNode) codec.encodeObservation(observation);
    assertThat(tree.size()).isEqualTo(5);
    assertThat(tree.has("board")).isTrue();
    assertThat(tree.has("yourSeat")).isTrue();
    assertThat(tree.has("currentSeat")).isTrue();
    assertThat(tree.has("legalColumns")).isTrue();
    assertThat(tree.has("moveCount")).isTrue();
    assertThat(tree.get("yourSeat").stringValue()).isEqualTo("B");
    assertThat(tree.get("currentSeat").stringValue()).isEqualTo("B");
    assertThat(tree.get("moveCount").intValue()).isEqualTo(1);
    assertThat(tree.get("legalColumns").toString()).isEqualTo("[0,1,2,3,4,5,6]");
    assertThat(tree.get("board").get(5).get(6).stringValue()).isEqualTo("A");
    ((ArrayNode) tree.get("board").get(5)).set(6, json.readTree("\"B\""));
    assertThat(observation.board().get(5).get(6)).isEqualTo("A");
    var encodedState = snapshot(state);
    ((ArrayNode) encodedState.get("board").get(5)).set(6, json.readTree("\"B\""));
    assertThat(state.board().get(5).get(6)).isEqualTo("A");
    assertThat(codec.encodeAction(new PlaceDisc("place_disc", 6)).toString())
        .isEqualTo("{\"type\":\"place_disc\",\"column\":6}");
    var terminal = play(0, 6, 1, 6, 2, 5, 3);
    assertThat(codec.encodeObservation(game.observe(terminal, a)).get("currentSeat").isNull()).isTrue();
    for (String text : List.of("{\"type\":\"place_disc\",\"column\":true}",
        "{\"type\":\"place_disc\",\"column\":2147483648}", "{\"column\":0}",
        "{\"type\":\"place_disc\"}", "{\"type\":1,\"column\":0}")) {
      assertThatThrownBy(() -> codec.decodeAction(json.readTree(text))).isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> codec.decodeAction(null)).isInstanceOf(IllegalArgumentException.class);
    for (PlaceDisc action : List.of(new PlaceDisc(null, 0), new PlaceDisc("bad", 0),
        new PlaceDisc("place_disc", -1), new PlaceDisc("place_disc", 7))) {
      assertThatThrownBy(() -> codec.encodeAction(action)).isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> codec.encodeAction(null)).isInstanceOf(IllegalArgumentException.class);
  }
}
