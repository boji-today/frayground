package io.frayground.config;

import io.frayground.adapter.game.GameCodec;
import io.frayground.adapter.game.GameRegistry;
import io.frayground.application.query.LaboratoryQueryService.Unavailable;
import io.frayground.application.query.LaboratoryReadModels.MatchData;
import io.frayground.domain.game.GameAction;
import io.frayground.domain.game.GameKey;
import io.frayground.domain.game.GameObservation;
import io.frayground.domain.game.GameState;
import io.frayground.domain.game.connectfour.ConnectFourState;
import io.frayground.web.BoardProjector;
import io.frayground.web.dto.LaboratoryDtos.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Decodes the registered snapshot without exposing its stored JSON wrapper. */
public final class ConnectFourViewProjector implements BoardProjector {
  private static final GameKey CONNECT_FOUR = new GameKey("connect-four", "1");
  private static final TypeReference<LinkedHashMap<String, Object>> OBJECT = new TypeReference<>() {};
  private final GameRegistry registry; private final JsonMapper json;
  private final JsonMapper snapshotJson=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
  public ConnectFourViewProjector(GameRegistry registry,JsonMapper json) { this.registry=registry; this.json=json; }
  @Override public Object project(MatchData data,ResultView result) {
    try {
      var codec=registry.codec(data.match().gameKey()).orElseThrow(Unavailable::new);
      JsonNode encoded=encodedState(data);
      var decoded=codec.decodeState(encoded);
      if(data.match().gameKey().equals(CONNECT_FOUR)) {
        if(!(decoded instanceof ConnectFourState state)) throw new Unavailable();
        return connectFour(data, state, result);
      }
      return json.convertValue(encode(codec, (GameState) decoded), OBJECT);
    } catch(RuntimeException ex) { throw new Unavailable(); }
  }
  private BoardView connectFour(MatchData data, ConnectFourState state, ResultView result) {
    Map<String,UUID> seats=new LinkedHashMap<>();
    data.match().participants().forEach(p->seats.put(p.seatKey(),p.participantId()));
    MoveView move=null;
    if(data.lastMove()!=null) {
      var p=data.lastMove().payload();
      move=new MoveView(((Number)p.get("row")).intValue(),((Number)p.get("column")).intValue(),
          (String)p.get("seat"),UUID.fromString(p.get("participantId").toString()));
    }
    return new BoardView(state.board(),Map.copyOf(seats),state.currentParticipantId(),state.moveCount(),
        state.winningCells().stream().map(c->new CellView(c.row(),c.column())).toList(),result,move);
  }
  @SuppressWarnings("unchecked")
  private static JsonNode encode(GameCodec<?,?,?> codec, GameState state) {
    return ((GameCodec<GameState, GameAction, GameObservation>) codec).encodeState(state);
  }
  private JsonNode encodedState(MatchData data) {
    var stored=data.snapshot().state();
    if(data.snapshot().stateVersion()==0) return json.valueToTree(stored);
    if(stored.size()!=2 || !"json-v1".equals(stored.get("_frayground_encoding"))
        || !(stored.get("_frayground_state") instanceof String value)) throw new Unavailable();
    return snapshotJson.readTree(value);
  }
}
