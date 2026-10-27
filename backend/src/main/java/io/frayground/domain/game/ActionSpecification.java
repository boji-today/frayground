package io.frayground.domain.game;

import java.util.List;
import java.util.Map;
import java.util.Objects;

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
