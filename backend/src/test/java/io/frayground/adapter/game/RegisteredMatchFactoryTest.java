package io.frayground.adapter.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.frayground.domain.game.ActionSpecification;
import io.frayground.domain.game.GameAction;
import io.frayground.domain.game.GameDefinition;
import io.frayground.domain.game.GameEvent;
import io.frayground.domain.game.GameEventDraft;
import io.frayground.domain.game.GameKey;
import io.frayground.domain.game.GameObservation;
import io.frayground.domain.game.GameOutcome;
import io.frayground.domain.game.GameResult;
import io.frayground.domain.game.GameState;
import io.frayground.domain.game.Participant;
import io.frayground.domain.game.Resolution;
import io.frayground.domain.game.SchedulingMode;
import io.frayground.domain.game.StateSnapshot;
import io.frayground.domain.game.TurnPlan;
import io.frayground.domain.game.ValidationResult;
import io.frayground.domain.game.VisibleGameEvent;
import io.frayground.domain.game.connectfour.ConnectFourDefinition;
import io.frayground.domain.game.connectfour.ConnectFourState;
import io.frayground.domain.match.MatchStatus;
import io.frayground.domain.match.TurnStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RegisteredMatchFactoryTest {
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test void createsConnectFourWithAllInitialFieldsAndFreshIdentity() {
    UUID aId = UUID.randomUUID();
    UUID bId = UUID.randomUUID();
    var a = participant(aId, "A");
    var b = participant(bId, "B");
    var game = new ConnectFourDefinition();
    var registration = new GameRegistration<>(game, new io.frayground.adapter.game.connectfour.ConnectFourCodec(),
        new io.frayground.adapter.game.connectfour.ConnectFourActionSchemaProvider());
    var ids = new AtomicLong();
    var factory = factory(registration, ids);
    UUID roomId = UUID.randomUUID();
    List<Participant> input = new ArrayList<>(List.of(b, a));

    var first = factory.create(roomId, game.key(), input);
    input.clear();
    assertThat(first.match().roomId()).isEqualTo(roomId);
    assertThat(first.match().gameKey()).isEqualTo(game.key());
    assertThat(first.match().participants()).containsExactly(b, a);
    assertThat(first.match().status()).isEqualTo(MatchStatus.READY);
    assertThat(first.match().stateVersion()).isZero();
    assertThat(first.match().gameEventSeq()).isZero();
    assertThat(first.match().result().status()).isEqualTo(GameOutcome.ONGOING);
    assertThat(first.firstTurn().turnId()).isEqualTo(first.match().currentTurnId());
    assertThat(first.firstTurn().status()).isEqualTo(TurnStatus.OPEN);
    assertThat(first.firstTurn().turnNo()).isEqualTo(1);
    assertThat(first.firstTurn().roundNo()).isEqualTo(1);
    assertThat(first.firstTurn().baseStateVersion()).isZero();
    assertThat(first.firstTurn().schedulingMode()).isEqualTo(SchedulingMode.TURN_BASED_SEQUENTIAL);
    assertThat(first.firstTurn().requiredParticipants()).containsExactly(aId);
    assertThat(first.initialSnapshot().matchId()).isEqualTo(first.match().matchId());
    assertThat(first.initialSnapshot().stateVersion()).isZero();
    assertThat(first.initialSnapshot().createdAt()).isEqualTo(NOW);
    var decoded = registration.codec().decodeState(JSON.valueToTree(first.initialSnapshot().state()));
    assertThat(decoded).isInstanceOf(ConnectFourState.class);
    assertThat(((ConnectFourState) decoded).board()).hasSize(6);
    assertThat(((ConnectFourState) decoded).currentParticipantId()).isEqualTo(aId);

    var second = factory.create(roomId, game.key(), List.of(a, b));
    assertThat(second.match().matchId()).isNotEqualTo(first.match().matchId());
    assertThat(second.firstTurn().turnId()).isNotEqualTo(first.firstTurn().turnId());
    assertThat(second.match().participants()).containsExactly(a, b);
    assertThat(a.participantId()).isEqualTo(aId);
  }

  @Test void isGenericAcrossThreeNonBoardSeatsAndDeepFreezesSnapshot() {
    var game = new MiniGame(GameOutcome.ONGOING, null);
    var codec = new MiniCodec();
    var registration = new GameRegistration<>(game, codec, schemaId -> null);
    var factory = factory(registration, new AtomicLong(100));
    var amber = participant(UUID.randomUUID(), "amber");
    var cobalt = participant(UUID.randomUUID(), "cobalt");
    var jade = participant(UUID.randomUUID(), "jade");
    List<Participant> input = new ArrayList<>(List.of(amber, cobalt, jade));
    var created = factory.create(UUID.randomUUID(), game.key(), input);
    input.clear();

    assertThat(created.match().participants()).containsExactly(amber, cobalt, jade);
    assertThat(created.firstTurn().schedulingMode()).isEqualTo(SchedulingMode.TURN_BASED_SIMULTANEOUS);
    assertThat(created.firstTurn().requiredParticipants())
        .containsExactly(amber.participantId(), cobalt.participantId(), jade.participantId());
    assertThat(created.initialSnapshot().state()).containsEntry("stock", 7);
    assertThatThrownBy(() -> created.initialSnapshot().state().put("stock", 0))
        .isInstanceOf(UnsupportedOperationException.class);
    Object metadata = created.initialSnapshot().state().get("metadata");
    assertThat(metadata).isInstanceOf(Map.class);
    Map<?, ?> metadataMap = (Map<?, ?>) metadata;
    assertThat(metadataMap.containsKey("optional")).isTrue();
    assertThat(metadataMap.get("optional")).isNull();
    assertThatThrownBy(() -> ((Map<Object, Object>) metadataMap).put("optional", "changed"))
        .isInstanceOf(UnsupportedOperationException.class);
    List<?> values = (List<?>) metadataMap.get("values");
    assertThat(values.toArray()).containsExactly("marker", null, Map.of("inner", "kept"));
    assertThatThrownBy(() -> ((List<Object>) values).add("changed"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> ((Map<Object, Object>) values.get(2)).put("inner", "changed"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(new MiniCodec().decodeState(JSON.valueToTree(created.initialSnapshot().state())))
        .isEqualTo(game.initialState);

    ((tools.jackson.databind.node.ObjectNode) codec.lastEncoded).put("stock", -1);
    assertThat(created.initialSnapshot().state()).containsEntry("stock", 7);
    game.initialState.metadata().put("afterCreation", true);
    assertThat(((Map<?, ?>) created.initialSnapshot().state().get("metadata"))
        .containsKey("afterCreation")).isFalse();
  }

  @Test void rejectsUnknownKeysDuplicateIdentitiesAndInvalidParticipantInputs() {
    var game = new ConnectFourDefinition();
    var factory = factory(new GameRegistration<>(game,
        new io.frayground.adapter.game.connectfour.ConnectFourCodec(),
        new io.frayground.adapter.game.connectfour.ConnectFourActionSchemaProvider()), new AtomicLong());
    UUID roomId = UUID.randomUUID();
    var a = participant(UUID.randomUUID(), "A");
    var b = participant(UUID.randomUUID(), "B");

    assertThatThrownBy(() -> factory.create(roomId, new GameKey("connect-four", "2"), List.of(a, b)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> factory.create(roomId, game.key(), List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> factory.create(roomId, game.key(), List.of(a)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> factory.create(roomId, game.key(), List.of(a,
        new Participant(a.participantId(), UUID.randomUUID(), "B"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> factory.create(roomId, game.key(), List.of(a,
        new Participant(UUID.randomUUID(), UUID.randomUUID(), "A"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> factory.create(roomId, game.key(), List.of(a,
        new Participant(UUID.randomUUID(), a.agentId(), "B"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> factory.create(null, game.key(), List.of(a, b)))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> factory.create(roomId, null, List.of(a, b)))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> factory.create(roomId, game.key(), null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test void rejectsBadInitialRulesResultAndUnknownTurnParticipant() {
    var participants = List.of(participant(UUID.randomUUID(), "north"),
        participant(UUID.randomUUID(), "east"), participant(UUID.randomUUID(), "south"));
    var ended = new MiniGame(GameOutcome.DRAW, null);
    var endedFactory = factory(new GameRegistration<>(ended, new MiniCodec(), schemaId -> null),
        new AtomicLong());
    assertThatThrownBy(() -> endedFactory.create(UUID.randomUUID(), ended.key(), participants))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ONGOING");

    var invalidTurn = new MiniGame(GameOutcome.ONGOING, UUID.randomUUID());
    var turnFactory = factory(new GameRegistration<>(invalidTurn, new MiniCodec(), schemaId -> null),
        new AtomicLong());
    assertThatThrownBy(() -> turnFactory.create(UUID.randomUUID(), invalidTurn.key(), participants))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown participant");
  }

  private static Participant participant(UUID participantId, String seat) {
    return new Participant(participantId, UUID.randomUUID(), seat);
  }

  private static RegisteredMatchFactory factory(GameRegistration<?, ?, ?> registration, AtomicLong ids) {
    return new RegisteredMatchFactory(new DefaultGameRegistry(List.of(registration)), JSON,
        Clock.fixed(NOW, ZoneOffset.UTC), () -> new UUID(0, ids.incrementAndGet()));
  }

  private record MiniState(int stock, Map<String, Object> metadata) implements GameState {}
  private record MiniAction(String command) implements GameAction {}
  private record MiniObservation(int stock) implements GameObservation {}

  /** Test-only non-board game with three simultaneous seats and nested nullable state data. */
  private static final class MiniGame implements GameDefinition<MiniState, MiniAction, MiniObservation> {
    private final GameOutcome initialOutcome;
    private final UUID forcedRequiredParticipant;
    private final MiniState initialState;

    private MiniGame(GameOutcome initialOutcome, UUID forcedRequiredParticipant) {
      this.initialOutcome = initialOutcome;
      this.forcedRequiredParticipant = forcedRequiredParticipant;
      Map<String, Object> metadata = new LinkedHashMap<>();
      metadata.put("optional", null);
      metadata.put("values", new ArrayList<>(List.of("marker", "discard")));
      ((List<Object>) metadata.get("values")).set(1, null);
      Map<String, Object> child = new LinkedHashMap<>();
      child.put("inner", "kept");
      ((List<Object>) metadata.get("values")).add(Collections.unmodifiableMap(child));
      this.initialState = new MiniState(7, metadata);
    }

    @Override public GameKey key() { return new GameKey("test-resource-pool", "1"); }
    @Override public MiniState initialize(List<Participant> participants) { return initialState; }
    @Override public TurnPlan nextTurn(MiniState state, List<Participant> participants) {
      List<UUID> required = forcedRequiredParticipant == null
          ? participants.stream().map(Participant::participantId).toList()
          : List.of(forcedRequiredParticipant);
      return new TurnPlan(SchedulingMode.TURN_BASED_SIMULTANEOUS, required);
    }
    @Override public MiniObservation observe(MiniState state, UUID participantId) {
      return new MiniObservation(state.stock());
    }
    @Override public ActionSpecification<MiniAction> actions(MiniState state, UUID participantId) {
      return new ActionSpecification<>(io.frayground.domain.game.ActionGenerationMode.STRUCTURED_ACTION,
          "test-resource-pool/action/1", List.of(), Map.of());
    }
    @Override public ValidationResult validate(MiniState state, UUID participantId, MiniAction action) {
      return new ValidationResult.Accepted();
    }
    @Override public Resolution<MiniState> resolve(MiniState state, Map<UUID, MiniAction> actions) {
      return new Resolution<>(state, List.<GameEventDraft>of(), result(state));
    }
    @Override public GameResult result(MiniState state) {
      return new GameResult(initialOutcome, List.of(), null);
    }
    @Override public List<VisibleGameEvent> visibleHistory(UUID participantId,
        List<GameEvent> authoritativeEvents) { return List.of(); }
  }

  private static final class MiniCodec implements GameCodec<MiniState, MiniAction, MiniObservation> {
    private JsonNode lastEncoded;
    @Override public JsonNode encodeState(MiniState state) {
      lastEncoded = JSON.valueToTree(state);
      return lastEncoded;
    }
    @Override public MiniState decodeState(JsonNode data) {
      return JSON.convertValue(data, MiniState.class);
    }
    @Override public JsonNode encodeObservation(MiniObservation observation) { return JSON.valueToTree(observation); }
    @Override public MiniAction decodeAction(JsonNode data) { return JSON.convertValue(data, MiniAction.class); }
    @Override public JsonNode encodeAction(MiniAction action) { return JSON.valueToTree(action); }
  }
}
