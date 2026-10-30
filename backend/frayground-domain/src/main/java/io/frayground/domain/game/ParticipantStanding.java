package io.frayground.domain.game;

import java.util.Objects;
import java.util.UUID;

/**
 * One participant's place in a match ranking.
 *
 * <p>Equal {@code rank} values are ties. Rank 1 identifies the winners.
 * {@code score} is optional and does not replace rank.
 */
public record ParticipantStanding(UUID participantId, int rank, Double score) {
  public ParticipantStanding {
    Objects.requireNonNull(participantId);
    if (rank < 1) {
      throw new IllegalArgumentException("rank");
    }
    if (score != null && !Double.isFinite(score)) {
      throw new IllegalArgumentException("score");
    }
  }
}
