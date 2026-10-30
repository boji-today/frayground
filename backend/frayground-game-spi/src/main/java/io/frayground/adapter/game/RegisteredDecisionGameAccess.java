package io.frayground.adapter.game;

import io.frayground.application.port.DecisionGameAccess;
import io.frayground.application.port.DecisionGameMetadata;
import io.frayground.domain.game.*;
import io.frayground.engine.GameEngine;
import io.frayground.engine.GameActionRejectedException;
import java.util.*;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Isolates registered generic rules and JSON conversion from application services. */
public final class RegisteredDecisionGameAccess implements DecisionGameAccess {
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
  private final GameRegistry registry;
  private final Map<GameKey, DecisionGameMetadata> metadata;
  private final Map<GameKey, SystemPhaseHandler> systemPhases;
  private final GameEngine engine;
  private final JsonMapper json;

  public RegisteredDecisionGameAccess(GameRegistry registry, Collection<DecisionGameMetadata> metadata,
      Collection<SystemPhaseHandler> systemPhases, GameEngine engine, JsonMapper json) {
    this.registry = Objects.requireNonNull(registry);
    this.engine = Objects.requireNonNull(engine);
    this.json = Objects.requireNonNull(json);
    Map<GameKey, DecisionGameMetadata> indexed = new HashMap<>();
    for (var item : metadata) if (indexed.putIfAbsent(item.gameKey(), item) != null)
      throw new IllegalArgumentException("duplicate game metadata");
    this.metadata = Map.copyOf(indexed);
    Map<GameKey, SystemPhaseHandler> phases = new HashMap<>();
    for (var handler : systemPhases) if (phases.putIfAbsent(handler.gameKey(), handler) != null)
      throw new IllegalArgumentException("duplicate system phase");
    this.systemPhases = Map.copyOf(phases);
  }
  private DecisionGameMetadata metadata(GameKey key) {
    var value = metadata.get(key);
    if (value == null) throw new IllegalArgumentException("GAME_METADATA_NOT_REGISTERED");
    return value;
  }
  private GameRegistration<?, ?, ?> registration(GameKey key) {
    return registry.registration(key).orElseThrow(() -> new IllegalArgumentException("GAME_NOT_REGISTERED"));
  }
  @Override public long roundNo(GameKey key, long turnNo) { return metadata(key).roundNo(turnNo); }
  @Override public Prepared prepare(GameKey key, long version, Map<String, Object> state, List<Participant> participants, UUID participantId) {
    return prepareRegistered(registration(key), metadata(key), version, state, participants, participantId);
  }
  private <S extends GameState, A extends GameAction, O extends GameObservation> Prepared prepareRegistered(
      GameRegistration<S,A,O> r, DecisionGameMetadata m, long version, Map<String,Object> encoded,
      List<Participant> participants, UUID participantId) {
    S state = r.codec().decodeState(decodeState(version,encoded));
    var spec = r.definition().actions(state, participantId);
    var options = new ArrayList<Map<String,Object>>();
    for (var option : spec.options()) options.add(Map.of("selectedActionId", option.selectedActionId(),
        "action", json.convertValue(r.codec().encodeAction(option.action()), MAP)));
    var specMap = Map.of("mode", spec.mode().name(), "actionSchemaId", spec.actionSchemaId(),
        "options", options, "constraints", spec.constraints());
    return new Prepared(r.definition().nextTurn(state, participants),
        json.writeValueAsString(r.codec().encodeObservation(r.definition().observe(state, participantId))),
        json.writeValueAsString(specMap), spec.actionSchemaId(),
        json.writeValueAsString(r.schemaProvider().schema(spec.actionSchemaId())), m.rulesText());
  }
  @Override public Optional<Applied> resolveSystem(GameKey key, long version, Map<String,Object> state, List<Participant> participants) {
    SystemPhaseHandler handler = systemPhases.get(key);
    if (handler == null) return Optional.empty();
    return resolveSystemRegistered(registration(key), handler, version, state, participants);
  }
  private <S extends GameState, A extends GameAction, O extends GameObservation> Optional<Applied> resolveSystemRegistered(
      GameRegistration<S,A,O> registration, SystemPhaseHandler handler, long version, Map<String,Object> encoded,
      List<Participant> participants) {
    S state = registration.codec().decodeState(decodeState(version, encoded));
    var resolution = handler.resolve(state);
    if (resolution.isEmpty()) return Optional.empty();
    if (!state.getClass().isInstance(resolution.get().newState())) throw new IllegalArgumentException("SYSTEM_STATE");
    @SuppressWarnings("unchecked") S nextState = (S) resolution.get().newState();
    var next = resolution.get().result().status() == GameOutcome.ONGOING
        ? registration.definition().nextTurn(nextState, participants) : null;
    var events = resolution.get().events().stream().map(event -> new GameEventDraft(event.type(),
        event.eventVersion(), json.convertValue(json.valueToTree(event.payload()), MAP))).toList();
    var encodedNext = json.convertValue(registration.codec().encodeState(nextState), MAP);
    return Optional.of(new Applied(encodeState(encodedNext), Map.of(), events, resolution.get().result(), next));
  }
  @Override public Applied apply(GameKey key, long version, Map<String,Object> state, List<Participant> participants,
      UUID participantId, Map<String,Object> action) {
    metadata(key);
    return applyRegistered(registration(key), version, state, participants, participantId, action);
  }
  private <S extends GameState, A extends GameAction, O extends GameObservation> Applied applyRegistered(
      GameRegistration<S,A,O> r, long version, Map<String,Object> encoded, List<Participant> participants,
      UUID participantId, Map<String,Object> payload) {
    S state = r.codec().decodeState(decodeState(version,encoded));
    A action;
    try { action = r.codec().decodeAction(json.valueToTree(payload)); }
    catch (IllegalArgumentException ex) { throw new GameActionRejectedException("INVALID_ACTION"); }
    var normalized = json.convertValue(r.codec().encodeAction(action), MAP);
    var resolution = engine.apply(r.definition(), state, Map.of(participantId, action));
    var next = resolution.result().status() == GameOutcome.ONGOING
        ? r.definition().nextTurn(resolution.newState(), participants) : null;
    var events = resolution.events().stream().map(event -> new GameEventDraft(event.type(),
        event.eventVersion(), json.convertValue(json.valueToTree(event.payload()), MAP))).toList();
    var nextState=json.convertValue(r.codec().encodeState(resolution.newState()), MAP);
    return new Applied(encodeState(nextState),
        normalized, events, resolution.result(), next);
  }
  private Map<String,Object> encodeState(Map<String,Object> state) {
    // StateSnapshot's top-level Map.copyOf cannot carry null; keep the codec's exact JSON in a wrapper.
    return Map.of("_frayground_encoding","json-v1","_frayground_state",json.writeValueAsString(state));
  }
  private tools.jackson.databind.JsonNode decodeState(long version,Map<String,Object> state) {
    if (version==0) return json.valueToTree(state);
    if (!isWrapped(state)) throw new IllegalArgumentException("INVALID_STATE_SNAPSHOT");
    return json.readTree((String)state.get("_frayground_state"));
  }
  private boolean isWrapped(Map<String,Object> state) {
    return state.size()==2 && Objects.equals(state.get("_frayground_encoding"),"json-v1")
        && state.get("_frayground_state") instanceof String;
  }
}
