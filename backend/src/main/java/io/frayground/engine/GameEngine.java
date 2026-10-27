package io.frayground.engine;

import io.frayground.domain.game.GameAction;
import io.frayground.domain.game.GameDefinition;
import io.frayground.domain.game.GameObservation;
import io.frayground.domain.game.GameState;
import io.frayground.domain.game.Resolution;
import java.util.Map;
import java.util.UUID;

/**
 * Applies one phase of actions through a game definition.
 *
 * <p>The caller supplies the definition, the current state, and the actions
 * for this phase, keyed by participant. A sequential phase has one entry.
 * This method does not choose an action, call a model, or persist the result.
 */
public interface GameEngine {
  <S extends GameState, A extends GameAction, O extends GameObservation>
      Resolution<S> apply(
          GameDefinition<S, A, O> definition, S state, Map<UUID, A> phaseActions);
}
