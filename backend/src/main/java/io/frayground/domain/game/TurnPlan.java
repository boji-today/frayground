package io.frayground.domain.game;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record TurnPlan(SchedulingMode mode, List<UUID> requiredParticipants) {
  public TurnPlan {
    Objects.requireNonNull(mode);
    requiredParticipants = List.copyOf(requiredParticipants);
  }
}
