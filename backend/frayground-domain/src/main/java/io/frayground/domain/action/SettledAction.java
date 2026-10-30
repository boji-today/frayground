package io.frayground.domain.action;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One action included in a phase settlement.
 */
public record SettledAction(
    UUID participantId,
    UUID decisionId,
    UUID actionId,
    Map<String, Object> action) {
  public SettledAction {
    Objects.requireNonNull(participantId);
    Objects.requireNonNull(decisionId);
    Objects.requireNonNull(actionId);
    Objects.requireNonNull(action);
    action = Map.copyOf(action);
  }
}
