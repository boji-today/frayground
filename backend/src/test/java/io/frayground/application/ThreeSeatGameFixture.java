package io.frayground.application;

import io.frayground.adapter.game.*;
import io.frayground.application.port.DecisionGameMetadata;
import io.frayground.domain.game.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Non-board game: third seat acts twice, then first seat; event count changes each phase. */
final class ThreeSeatGameFixture {
  static final GameKey KEY=new GameKey("fixture","1");
  record State(int count,List<UUID> order) implements GameState { public State { order=List.copyOf(order); } }
  record Action(String kind) implements GameAction {}
  record Observation(int counter,String note) implements GameObservation {}
  static class Rules implements GameDefinition<State,Action,Observation> {
    public GameKey key() { return KEY; }
    public State initialize(List<Participant> participants) { return new State(0,participants.stream().map(Participant::participantId).toList()); }
    public TurnPlan nextTurn(State state,List<Participant> participants) {
      return new TurnPlan(SchedulingMode.TURN_BASED_SEQUENTIAL,List.of(state.order().get(state.count()<2?2:0)));
    }
    public Observation observe(State state,UUID id) { return new Observation(state.count(),"private:"+id); }
    public ActionSpecification<Action> actions(State state,UUID id) {
      return new ActionSpecification<>(ActionGenerationMode.ACTION_SELECTION,"fixture/action/1",List.of(new ActionOption<>("advance",new Action("advance"))),Map.of());
    }
    public ValidationResult validate(State state,UUID id,Action action) {
      return id.equals(state.order().get(state.count()<2?2:0)) && action.kind().equals("advance") ? new ValidationResult.Accepted() : new ValidationResult.Rejected("ILLEGAL");
    }
    public Resolution<State> resolve(State state,Map<UUID,Action> actions) {
      var next=new State(state.count()+1,state.order()); var events=new ArrayList<GameEventDraft>();
      for (int i=0;i<state.count();i++) events.add(new GameEventDraft("TICK",1,Map.of("number",i)));
      return new Resolution<>(next,events,result(next));
    }
    public GameResult result(State state) { return new GameResult(state.count()>=3?GameOutcome.DRAW:GameOutcome.ONGOING,List.of(),null); }
    public List<VisibleGameEvent> visibleHistory(UUID id,List<GameEvent> events) { return List.of(); }
  }
  static class Codec implements GameCodec<State,Action,Observation> {
    final JsonMapper json=JsonMapper.builder().build();
    public JsonNode encodeState(State state) { return json.valueToTree(state); }
    public State decodeState(JsonNode data) { return json.treeToValue(data,State.class); }
    public JsonNode encodeObservation(Observation observation) { return json.valueToTree(observation); }
    public Action decodeAction(JsonNode data) { if (!data.isObject()||data.size()!=1||!data.has("kind")||!data.get("kind").isString()) throw new IllegalArgumentException("action"); return new Action(data.get("kind").asString()); }
    public JsonNode encodeAction(Action action) { return json.valueToTree(action); }
  }
  static GameRegistration<State,Action,Observation> registration() {
    JsonMapper json=JsonMapper.builder().build();
    return new GameRegistration<>(new Rules(),new Codec(),id->json.readTree("{\"type\":\"object\"}"));
  }
  static DecisionGameMetadata metadata() { return new DecisionGameMetadata() {
    public GameKey gameKey() { return KEY; }
    public String rulesText() { return "advance counter"; }
    public long roundNo(long turnNo) { return 10+turnNo/3; }
  }; }
}
