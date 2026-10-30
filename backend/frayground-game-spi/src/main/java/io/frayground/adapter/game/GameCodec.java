package io.frayground.adapter.game;

import io.frayground.domain.game.GameAction;
import io.frayground.domain.game.GameObservation;
import io.frayground.domain.game.GameState;
import tools.jackson.databind.JsonNode;

/**
 * Converts one game's state, observation, and action at the JSON boundary.
 *
 * <p>Rules methods receive the decoded types. This codec is the only place
 * those types meet {@code JsonNode}.
 */
public interface GameCodec<
    S extends GameState, A extends GameAction, O extends GameObservation> {

  JsonNode encodeState(S state);

  S decodeState(JsonNode data);

  JsonNode encodeObservation(O observation);

  A decodeAction(JsonNode data);

  JsonNode encodeAction(A action);
}
