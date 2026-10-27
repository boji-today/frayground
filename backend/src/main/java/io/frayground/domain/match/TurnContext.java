package io.frayground.domain.match;

import io.frayground.domain.game.SchedulingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One action-collection phase inside a {@link Match}.
 *
 * <p>{@code turnNo} and {@code roundNo} both start at 1. {@code roundNo} is the
 * game's own round; {@code turnNo} numbers this phase. {@code baseStateVersion}
 * is the match state version the phase is based on. A sequential phase lists
 * one required participant; a simultaneous phase lists everyone who must act,
 * and their decisions share this {@code turnId}. {@code status} is
 * {@code OPEN}, {@code RESOLVED}, or {@code CANCELLED}. Failed attempts may be
 * retried within the phase. Only one successful settlement advances the match
 * {@code stateVersion}, and it advances that version once.
 */
public record TurnContext(
    UUID turnId,
    long turnNo,
    long roundNo,
    long baseStateVersion,
    SchedulingMode schedulingMode,
    List<UUID> requiredParticipants,
    TurnStatus status) {

  public TurnContext {
    Objects.requireNonNull(turnId);
    Objects.requireNonNull(schedulingMode);
    Objects.requireNonNull(status);
    if (turnNo < 1 || roundNo < 1 || baseStateVersion < 0) {
      throw new IllegalArgumentException("turn");
    }
    requiredParticipants = List.copyOf(requiredParticipants);
    Set<UUID> participants = new HashSet<>();
    for (UUID participantId : requiredParticipants) {
      if (participantId == null || !participants.add(participantId)) {
        throw new IllegalArgumentException("requiredParticipants");
      }
    }
    if (schedulingMode == SchedulingMode.TURN_BASED_SEQUENTIAL
        && requiredParticipants.size() != 1) {
      throw new IllegalArgumentException("requiredParticipants");
    }
    if (schedulingMode == SchedulingMode.TURN_BASED_SIMULTANEOUS
        && requiredParticipants.isEmpty()) {
      throw new IllegalArgumentException("requiredParticipants");
    }
  }
}
