package io.frayground.adapter.game;

import io.frayground.application.InitializedMatch;
import io.frayground.application.port.MatchFactory;
import io.frayground.domain.game.GameAction;
import io.frayground.domain.game.GameKey;
import io.frayground.domain.game.GameObservation;
import io.frayground.domain.game.GameOutcome;
import io.frayground.domain.game.GameState;
import io.frayground.domain.game.Participant;
import io.frayground.domain.game.StateSnapshot;
import io.frayground.domain.game.TurnPlan;
import io.frayground.domain.match.Match;
import io.frayground.domain.match.MatchStatus;
import io.frayground.domain.match.TurnContext;
import io.frayground.domain.match.TurnStatus;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Builds an initial Match, its first TurnContext, and a codec-encoded snapshot. */
public final class RegisteredMatchFactory implements MatchFactory {
  private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {};

  private final GameRegistry registry;
  private final JsonMapper jsonMapper;
  private final Clock clock;
  private final Supplier<UUID> ids;

  public RegisteredMatchFactory(
      GameRegistry registry, JsonMapper jsonMapper, Clock clock, Supplier<UUID> ids) {
    this.registry = Objects.requireNonNull(registry);
    this.jsonMapper = Objects.requireNonNull(jsonMapper);
    this.clock = Objects.requireNonNull(clock);
    this.ids = Objects.requireNonNull(ids);
  }

  @Override
  public InitializedMatch create(UUID roomId, GameKey gameKey, List<Participant> participants) {
    Objects.requireNonNull(roomId, "roomId");
    Objects.requireNonNull(gameKey, "gameKey");
    Objects.requireNonNull(participants, "participants");
    List<Participant> copiedParticipants = List.copyOf(participants);
    validateParticipants(copiedParticipants);
    GameRegistration<?, ?, ?> registration = registry.registration(gameKey)
        .orElseThrow(() -> new IllegalArgumentException("unregistered game: " + gameKey));
    return createRegistered(roomId, gameKey, copiedParticipants, registration);
  }

  private <S extends GameState, A extends GameAction, O extends GameObservation>
      InitializedMatch createRegistered(UUID roomId, GameKey requestedKey,
          List<Participant> participants, GameRegistration<S, A, O> registration) {
    if (!registration.definition().key().equals(requestedKey)) {
      throw new IllegalArgumentException("unregistered game: " + requestedKey);
    }
    S state = Objects.requireNonNull(registration.definition().initialize(participants));
    var result = Objects.requireNonNull(registration.definition().result(state));
    if (result.status() != GameOutcome.ONGOING) {
      throw new IllegalArgumentException("initial game result must be ONGOING");
    }
    TurnPlan plan = Objects.requireNonNull(registration.definition().nextTurn(state, participants));
    Set<UUID> participantIds = new HashSet<>();
    participants.forEach(participant -> participantIds.add(participant.participantId()));
    if (plan.requiredParticipants().stream().anyMatch(id -> !participantIds.contains(id))) {
      throw new IllegalArgumentException("turn requires an unknown participant");
    }

    UUID matchId = Objects.requireNonNull(ids.get(), "matchId");
    UUID turnId = null;
    TurnContext turn = null;
    if (!plan.requiredParticipants().isEmpty()) {
      turnId = Objects.requireNonNull(ids.get(), "turnId");
      turn = new TurnContext(turnId, 1, 1, 0, plan.mode(),
          plan.requiredParticipants(), TurnStatus.OPEN);
    }
    Match match = new Match(matchId, roomId, requestedKey, MatchStatus.READY,
        0, 0, turnId, participants, result);
    Map<String, Object> encoded = jsonMapper.convertValue(
        registration.codec().encodeState(state), OBJECT_MAP);
    StateSnapshot snapshot = new StateSnapshot(matchId, 0, deepImmutableMap(encoded), clock.instant());
    return new InitializedMatch(match, turn, snapshot);
  }

  private static void validateParticipants(List<Participant> participants) {
    Set<UUID> participantIds = new HashSet<>();
    Set<UUID> agentIds = new HashSet<>();
    Set<String> seatKeys = new HashSet<>();
    for (Participant participant : participants) {
      Objects.requireNonNull(participant, "participant");
      if (!participantIds.add(participant.participantId())
          || !agentIds.add(participant.agentId())
          || !seatKeys.add(participant.seatKey())) {
        throw new IllegalArgumentException("duplicate participant identity or seat");
      }
    }
  }

  private static Map<String, Object> deepImmutableMap(Map<String, Object> input) {
    Map<String, Object> copied = new LinkedHashMap<>();
    input.forEach((key, value) -> copied.put(key, deepImmutable(value)));
    return Collections.unmodifiableMap(copied);
  }

  private static Object deepImmutable(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<Object, Object> copied = new LinkedHashMap<>();
      map.forEach((key, nested) -> copied.put(key, deepImmutable(nested)));
      return Collections.unmodifiableMap(copied);
    }
    if (value instanceof List<?> list) {
      List<Object> copied = new ArrayList<>(list.size());
      list.forEach(nested -> copied.add(deepImmutable(nested)));
      return Collections.unmodifiableList(copied);
    }
    return value;
  }
}
