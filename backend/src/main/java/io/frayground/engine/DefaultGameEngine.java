package io.frayground.engine;

import io.frayground.domain.game.*;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Validates every phase action before handing the phase to its rules. */
public final class DefaultGameEngine implements GameEngine {
  @Override
  public <S extends GameState, A extends GameAction, O extends GameObservation>
      Resolution<S> apply(GameDefinition<S, A, O> definition, S state, Map<UUID, A> phaseActions) {
    Objects.requireNonNull(definition);
    Objects.requireNonNull(state);
    Objects.requireNonNull(phaseActions);
    for (var entry : phaseActions.entrySet()) {
      ValidationResult result = Objects.requireNonNull(
          definition.validate(state, entry.getKey(), entry.getValue()));
      if (result instanceof ValidationResult.Rejected rejected) {
        throw new GameActionRejectedException(rejected.code());
      }
    }
    return definition.resolve(state, Map.copyOf(phaseActions));
  }
}
