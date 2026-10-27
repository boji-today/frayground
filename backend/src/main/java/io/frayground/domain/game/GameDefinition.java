package io.frayground.domain.game;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface GameDefinition<
    S extends GameState, A extends GameAction, O extends GameObservation> {

  GameKey key();

  S initialize(List<Participant> participants);

  TurnPlan nextTurn(S state, List<Participant> participants);

  O observe(S state, UUID participantId);

  ActionSpecification<A> actions(S state, UUID participantId);

  ValidationResult validate(S state, UUID participantId, A action);

  Resolution<S> resolve(S state, Map<UUID, A> phaseActions);

  GameResult result(S state);

  List<VisibleGameEvent> visibleHistory(
      UUID participantId, List<GameEvent> authoritativeEvents);
}
