package io.frayground.domain.game;

import java.util.Objects;

/**
 * One candidate in the current decision.
 *
 * <p>{@code selectedActionId} is local to that decision's option list and is
 * not the submission {@code actionId}. {@code action} is the typed payload
 * used after a selection.
 */
public record ActionOption<A extends GameAction>(String selectedActionId, A action) {
  public ActionOption {
    if (selectedActionId == null || selectedActionId.isBlank()) {
      throw new IllegalArgumentException("selectedActionId");
    }
    Objects.requireNonNull(action);
  }
}
