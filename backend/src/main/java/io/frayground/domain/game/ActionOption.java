package io.frayground.domain.game;

import java.util.Objects;

public record ActionOption<A extends GameAction>(String selectedActionId, A action) {
  public ActionOption {
    if (selectedActionId == null || selectedActionId.isBlank()) {
      throw new IllegalArgumentException("selectedActionId");
    }
    Objects.requireNonNull(action);
  }
}
