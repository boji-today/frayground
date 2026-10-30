package io.frayground.domain.game;

import java.util.Objects;
import java.util.UUID;

/**
 * One seat in a single match.
 *
 * <p>{@code participantId} identifies the seat for this match and is replaced
 * after reset. {@code agentId} is the decision entity bound to the seat.
 * {@code seatKey} is the rules-facing seat name, such as {@code A} or {@code B},
 * and is unique within the match. This record does not say who may edit the
 * agent's prompt.
 */
public record Participant(UUID participantId, UUID agentId, String seatKey) {
  public Participant {
    Objects.requireNonNull(participantId);
    Objects.requireNonNull(agentId);
    if (seatKey == null || seatKey.isBlank()) {
      throw new IllegalArgumentException("seatKey");
    }
  }
}
