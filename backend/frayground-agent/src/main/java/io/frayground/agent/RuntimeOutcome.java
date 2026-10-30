package io.frayground.agent;

import java.util.Objects;

/**
 * Candidate or failure returned by a runtime.
 *
 * <p>{@code SelectedAction} names an option in this decision.
 * {@code StructuredAction} carries the game action as JSON.
 * {@code Failed} carries a runtime error. None of these changes the match.
 */
public sealed interface RuntimeOutcome
    permits RuntimeOutcome.SelectedAction, RuntimeOutcome.StructuredAction, RuntimeOutcome.Failed {

  record SelectedAction(String selectedActionId) implements RuntimeOutcome {
    public SelectedAction {
      if (selectedActionId == null || selectedActionId.isBlank()) {
        throw new IllegalArgumentException("selectedActionId");
      }
    }
  }

  record StructuredAction(String actionJson) implements RuntimeOutcome {
    public StructuredAction {
      if (actionJson == null || actionJson.isBlank()) {
        throw new IllegalArgumentException("actionJson");
      }
    }
  }

  record Failed(RuntimeError error) implements RuntimeOutcome {
    public Failed {
      Objects.requireNonNull(error);
    }
  }
}
