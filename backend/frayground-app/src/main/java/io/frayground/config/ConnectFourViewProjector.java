package io.frayground.config;

import io.frayground.adapter.game.GameRegistry;
import io.frayground.application.query.LaboratoryQueryService.Unavailable;
import io.frayground.application.query.LaboratoryReadModels.MatchData;
import io.frayground.domain.game.GameKey;
import io.frayground.domain.game.connectfour.ConnectFourState;
import io.frayground.web.BoardProjector;
import io.frayground.web.dto.LaboratoryDtos.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Decodes the registered snapshot without exposing its stored JSON. */
public final class ConnectFourViewProjector implements BoardProjector {
  private final GameRegistry registry; private final JsonMapper json;
  private final JsonMapper snapshotJson=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
  public ConnectFourViewProjector(GameRegistry registry,JsonMapper json) { this.registry=registry; this.json=json; }
  @Override public BoardView project(MatchData data,ResultView result) {
    try {
      if(!data.match().gameKey().equals(new GameKey("connect-four","1")))throw new Unavailable();
      var codec=registry.codec(data.match().gameKey()).orElseThrow(Unavailable::new);
      var stored=data.snapshot().state();
      tools.jackson.databind.JsonNode encoded;
      if(data.snapshot().stateVersion()==0) encoded=json.valueToTree(stored);
      else {
        if(stored.size()!=2 || !"json-v1".equals(stored.get("_frayground_encoding"))
            || !(stored.get("_frayground_state") instanceof String value))throw new Unavailable();
        encoded=snapshotJson.readTree(value);
      }
      var decoded=codec.decodeState(encoded);
      if(!(decoded instanceof ConnectFourState state))throw new Unavailable();
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
    } catch(RuntimeException ex) { throw new Unavailable(); }
  }
}
