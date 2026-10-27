package io.frayground.domain.action;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One authoritative settlement of a turn.
 *
 * <p>A sequential turn carries one {@link SettledAction}. A simultaneous turn
 * carries one action for each participant who acted. The phase advances the
 * match {@code stateVersion} once: {@code toVersion} is {@code fromVersion + 1}.
 * This record does not implement action collection, deadlines, or persistence.
 */
public record Settlement(
    UUID settlementId,
    UUID matchId,
    UUID turnId,
    long fromVersion,
    long toVersion,
    List<SettledAction> actions) {
  public Settlement {
    Objects.requireNonNull(settlementId);
    Objects.requireNonNull(matchId);
    Objects.requireNonNull(turnId);
    actions = List.copyOf(actions);
    if (actions.isEmpty() || fromVersion < 0 || toVersion != fromVersion + 1) {
      throw new IllegalArgumentException("settlement");
    }
    Set<UUID> participants = new HashSet<>();
    Set<UUID> decisions = new HashSet<>();
    Set<UUID> actionIds = new HashSet<>();
    for (SettledAction action : actions) {
      Objects.requireNonNull(action);
      if (!participants.add(action.participantId())
          || !decisions.add(action.decisionId())
          || !actionIds.add(action.actionId())) {
        throw new IllegalArgumentException("actions");
      }
    }
  }
}
