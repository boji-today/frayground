package io.frayground.adapter.game;

import io.frayground.domain.game.GameAction;
import io.frayground.domain.game.GameDefinition;
import io.frayground.domain.game.GameObservation;
import io.frayground.domain.game.GameState;
import java.util.Objects;

/** One explicitly registered game and its matching JSON/action adapters. */
public record GameRegistration<S extends GameState, A extends GameAction, O extends GameObservation>(
    GameDefinition<S, A, O> definition,
    GameCodec<S, A, O> codec,
    ActionSchemaProvider schemaProvider) {
  public GameRegistration {
    Objects.requireNonNull(definition);
    Objects.requireNonNull(codec);
    Objects.requireNonNull(schemaProvider);
    Objects.requireNonNull(definition.key());
  }
}
