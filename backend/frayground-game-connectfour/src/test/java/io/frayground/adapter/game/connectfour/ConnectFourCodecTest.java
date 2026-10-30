package io.frayground.adapter.game.connectfour;
import io.frayground.domain.game.Participant;
import io.frayground.domain.game.connectfour.ConnectFourDefinition;
import io.frayground.domain.game.connectfour.PlaceDisc;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
class ConnectFourCodecTest {
  final JsonMapper json = JsonMapper.builder().build();
  final ConnectFourCodec codec = new ConnectFourCodec();
  @Test void roundTripAndStrictActions() {
    var a = UUID.randomUUID();
    var b = UUID.randomUUID();
    var agent = UUID.randomUUID();
    var game = new ConnectFourDefinition();
    var state=game.initialize(List.of(new Participant(a,agent,"A"),new Participant(b,agent,"B")));
    assertThat(codec.decodeState(codec.encodeState(state))).isEqualTo(state);
    assertThat(codec.decodeAction(json.readTree("{\"type\":\"place_disc\",\"column\":3}"))).isEqualTo(new PlaceDisc("place_disc",3));
    for (String text : List.of("{}", "null", "[]", "{\"type\":null,\"column\":0}",
        "{\"type\":\"bad\",\"column\":0}", "{\"type\":\"place_disc\",\"column\":null}",
        "{\"type\":\"place_disc\",\"column\":\"3\"}", "{\"type\":\"place_disc\",\"column\":3.0}",
        "{\"type\":\"place_disc\",\"column\":-1}", "{\"type\":\"place_disc\",\"column\":7}",
        "{\"type\":\"place_disc\",\"column\":3,\"x\":1}")) {
      assertThatThrownBy(() -> codec.decodeAction(json.readTree(text))).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test void rejectsMalformedSnapshotsAndDoesNotShareEncodedTree() {
    var a = UUID.randomUUID();
    var b = UUID.randomUUID();
    var agent = UUID.randomUUID();
    var game = new ConnectFourDefinition();
    var initial = game.initialize(List.of(new Participant(a, agent, "A"), new Participant(b, agent, "B")));
    var middle = game.resolve(initial, Map.of(a, new PlaceDisc("place_disc", 6))).newState();
    assertThat(codec.decodeState(codec.encodeState(middle))).isEqualTo(middle);
    var tree = (tools.jackson.databind.node.ObjectNode) codec.encodeState(middle);
    tree.put("moveCount", 9);
    assertThat(middle.moveCount()).isEqualTo(1);
    assertThatThrownBy(() -> codec.decodeState(tree)).isInstanceOf(IllegalArgumentException.class);
    for (String field : List.of("board", "participantOrder", "currentParticipantId", "moveCount", "winningCells", "result")) {
      var missing = (tools.jackson.databind.node.ObjectNode) codec.encodeState(initial);
      missing.remove(field);
      assertThatThrownBy(() -> codec.decodeState(missing)).isInstanceOf(IllegalArgumentException.class);
    }
    var extra = (tools.jackson.databind.node.ObjectNode) codec.encodeState(initial);
    extra.put("extra", true);
    assertThatThrownBy(() -> codec.decodeState(extra)).isInstanceOf(IllegalArgumentException.class);
    var badId = (tools.jackson.databind.node.ObjectNode) codec.encodeState(initial);
    badId.put("currentParticipantId", "invalid");
    assertThatThrownBy(() -> codec.decodeState(badId)).isInstanceOf(IllegalArgumentException.class);
    var nested = (tools.jackson.databind.node.ObjectNode) codec.encodeState(initial);
    ((tools.jackson.databind.node.ObjectNode) nested.get("result")).put("extra", true);
    assertThatThrownBy(() -> codec.decodeState(nested)).isInstanceOf(IllegalArgumentException.class);
  }
}
