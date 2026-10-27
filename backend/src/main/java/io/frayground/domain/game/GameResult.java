package io.frayground.domain.game;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Rules outcome attached to a {@link io.frayground.domain.match.Match}.
 *
 * <p>{@code status} is {@code ONGOING}, {@code WIN}, or {@code DRAW}. A win or
 * draw moves an active match to {@code FINISHED}. Aborting a match is not
 * represented here. {@code standings} ranks any number of participants: equal
 * ranks are ties, and rank 1 identifies the winners. A {@code WIN} has at least
 * one rank 1. The same result covers a head-to-head match, a melee, and a full
 * ranking. {@code reason} is an optional rules explanation.
 */
public record GameResult(
    GameOutcome status,
    List<ParticipantStanding> standings,
    String reason) {

  public GameResult {
    Objects.requireNonNull(status);
    standings = List.copyOf(standings);
    Set<UUID> participants = new HashSet<>();
    boolean hasWinner = false;
    for (ParticipantStanding standing : standings) {
      Objects.requireNonNull(standing);
      if (!participants.add(standing.participantId())) {
        throw new IllegalArgumentException("standings");
      }
      if (standing.rank() == 1) {
        hasWinner = true;
      }
    }
    if (status == GameOutcome.WIN && !hasWinner) {
      throw new IllegalArgumentException("standings");
    }
  }
}
