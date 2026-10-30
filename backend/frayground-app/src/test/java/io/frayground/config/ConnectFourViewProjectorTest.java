package io.frayground.config;

import static org.junit.jupiter.api.Assertions.*;
import io.frayground.adapter.game.*;
import io.frayground.adapter.game.connectfour.*;
import io.frayground.application.query.LaboratoryQueryService.Unavailable;
import io.frayground.application.query.LaboratoryReadModels.MatchData;
import io.frayground.domain.game.*;
import io.frayground.domain.game.connectfour.ConnectFourDefinition;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ConnectFourViewProjectorTest {
  @Test void wrappedSnapshotsRequireExactEncodingAndValidJson() {
    var json=JsonMapper.builder().build();
    var registry=new DefaultGameRegistry(List.of(new GameRegistration<>(new ConnectFourDefinition(),
        new ConnectFourCodec(),new ConnectFourActionSchemaProvider())));
    var factory=new RegisteredMatchFactory(registry,json,Clock.systemUTC(),UUID::randomUUID);
    var initial=factory.create(UUID.randomUUID(),new GameKey("connect-four","1"),List.of(
        new Participant(UUID.randomUUID(),UUID.randomUUID(),"A"),new Participant(UUID.randomUUID(),UUID.randomUUID(),"B")));
    var projector=new ConnectFourViewProjector(registry,json);
    var wrapped=Map.<String,Object>of("_frayground_encoding","json-v1",
        "_frayground_state",json.writeValueAsString(initial.initialSnapshot().state()));
    var snapshot=new StateSnapshot(initial.match().matchId(),1,wrapped,Instant.EPOCH);
    assertEquals(0,projector.project(new MatchData(initial.match(),initial.firstTurn(),snapshot,null,List.of()),null).moveCount());
    for(var invalid:List.of(Map.<String,Object>of("_frayground_encoding","json-v2","_frayground_state","{}"),
        Map.<String,Object>of("_frayground_encoding","json-v1","_frayground_state","{} {}"),
        Map.<String,Object>of("_frayground_encoding","json-v1","_frayground_state","{}","extra",true))) {
      var bad=new StateSnapshot(initial.match().matchId(),1,invalid,Instant.EPOCH);
      assertThrows(Unavailable.class,()->projector.project(new MatchData(initial.match(),initial.firstTurn(),bad,null,List.of()),null));
    }
  }
}
