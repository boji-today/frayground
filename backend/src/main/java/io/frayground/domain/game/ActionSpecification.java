package io.frayground.domain.game;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Actions one seat may submit from the current state.
 *
 * <p>{@code actionSchemaId} names an external schema; the schema document is
 * not stored here. {@code options} is the immutable candidate table for
 * {@link ActionGenerationMode#ACTION_SELECTION}. {@code constraints} carries
 * extra limits for the same state.
 */
public record ActionSpecification<A extends GameAction>(
    ActionGenerationMode mode,
    String actionSchemaId,
    List<ActionOption<A>> options,
    Map<String, Object> constraints) {

  public ActionSpecification {
    Objects.requireNonNull(mode);
    if (actionSchemaId == null || actionSchemaId.isBlank()) {
      throw new IllegalArgumentException("actionSchemaId");
    }
    options = List.copyOf(options);
    constraints = Map.copyOf(constraints);
  }
}
