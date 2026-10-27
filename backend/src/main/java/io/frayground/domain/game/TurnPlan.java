package io.frayground.domain.game;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Rules plan for the next action phase.
 *
 * <p>{@code requiredParticipants} is one seat in sequential mode and every
 * seat that must act in simultaneous mode. The plan does not open more than
 * one current turn.
 */
public record TurnPlan(SchedulingMode mode, List<UUID> requiredParticipants) {
  public TurnPlan {
    Objects.requireNonNull(mode);
    requiredParticipants = List.copyOf(requiredParticipants);
  }
}
