package io.frayground.config;

import static org.junit.jupiter.api.Assertions.*;
import io.frayground.adapter.game.*;
import io.frayground.adapter.game.connectfour.*;
import io.frayground.application.query.LaboratoryQueryService.Unavailable;
import io.frayground.application.query.LaboratoryReadModels.MatchData;
import io.frayground.domain.game.*;
import io.frayground.domain.game.connectfour.ConnectFourDefinition;
import io.frayground.web.dto.LaboratoryDtos.BoardView;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
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
    assertEquals(0,((BoardView)projector.project(new MatchData(initial.match(),initial.firstTurn(),snapshot,null,List.of()),null)).moveCount());
    for(var invalid:List.of(Map.<String,Object>of("_frayground_encoding","json-v2","_frayground_state","{}"),
        Map.<String,Object>of("_frayground_encoding","json-v1","_frayground_state","{} {}"),
        Map.<String,Object>of("_frayground_encoding","json-v1","_frayground_state","{}","extra",true))) {
      var bad=new StateSnapshot(initial.match().matchId(),1,invalid,Instant.EPOCH);
      assertThrows(Unavailable.class,()->projector.project(new MatchData(initial.match(),initial.firstTurn(),bad,null,List.of()),null));
    }
  }
  @Test void otherRegisteredGamesProjectCodecState() {
    var json=JsonMapper.builder().build();
    GameKey key=new GameKey("phase-sample","1");
    GameCodec<Phase, UnusedAction, UnusedObservation> codec=new PhaseCodec();
    GameRegistry registry=new GameRegistry() {
      public Optional<GameRegistration<?,?,?>> registration(GameKey requested) { return Optional.empty(); }
      public Optional<GameCodec<?,?,?>> codec(GameKey requested) { return requested.equals(key)?Optional.of(codec):Optional.empty(); }
    };
    var projector=new ConnectFourViewProjector(registry,json);
    UUID matchId=UUID.randomUUID(); UUID roomId=UUID.randomUUID();
    var participant=new Participant(UUID.randomUUID(),UUID.randomUUID(),"P");
    var match=new io.frayground.domain.match.Match(matchId,roomId,key,io.frayground.domain.match.MatchStatus.READY,0,0,null,
        List.of(participant),new GameResult(GameOutcome.ONGOING,List.of(),null));
    var snapshot=new StateSnapshot(matchId,0,Map.of("phase","open"),Instant.EPOCH);
    var projected=projector.project(new MatchData(match,null,snapshot,null,List.of()),null);
    assertInstanceOf(Map.class,projected);
    assertEquals("open",((Map<?,?>)projected).get("phase"));
    var missing=new io.frayground.domain.match.Match(matchId,roomId,new GameKey("missing","1"),io.frayground.domain.match.MatchStatus.READY,0,0,null,
        List.of(participant),new GameResult(GameOutcome.ONGOING,List.of(),null));
    assertThrows(Unavailable.class,()->projector.project(new MatchData(missing,null,snapshot,null,List.of()),null));
  }
  record Phase(String phase) implements GameState {}
  record UnusedAction() implements GameAction {}
  record UnusedObservation() implements GameObservation {}
  static final class PhaseCodec implements GameCodec<Phase, UnusedAction, UnusedObservation> {
    private final JsonMapper json=JsonMapper.builder().build();
    public JsonNode encodeState(Phase state) { return json.createObjectNode().put("phase", state.phase()); }
    public Phase decodeState(JsonNode data) { return new Phase(data.path("phase").asString()); }
    public JsonNode encodeObservation(UnusedObservation observation) { throw new UnsupportedOperationException(); }
    public UnusedAction decodeAction(JsonNode data) { throw new UnsupportedOperationException(); }
    public JsonNode encodeAction(UnusedAction action) { throw new UnsupportedOperationException(); }
  }
}
