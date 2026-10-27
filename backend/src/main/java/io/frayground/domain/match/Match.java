package io.frayground.domain.match;

import io.frayground.domain.game.GameKey;
import io.frayground.domain.game.GameResult;
import io.frayground.domain.game.Participant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One game inside a {@link Room}. Distinct from the room, which survives reset.
 *
 * <p>{@code status} is the match lifecycle and is orthogonal to the room's
 * execution schedule. {@code READY} becomes {@code ACTIVE} on start or step.
 * A rules win or draw moves {@code ACTIVE} to {@code FINISHED}. Reset aborts a
 * {@code READY} or {@code ACTIVE} match; {@code ABORTED} is run termination,
 * not a fabricated win. {@code stateVersion} starts at 0 and increases only on
 * a successful phase settlement, once for the whole phase.
 * {@code participants} is not limited to two seats. {@code currentTurnId} is
 * the single open action phase, shared by every participant who must act in
 * that phase. {@code result} ranks those participants.
 */
public record Match(
    UUID matchId,
    UUID roomId,
    GameKey gameKey,
    MatchStatus status,
    long stateVersion,
    long gameEventSeq,
    UUID currentTurnId,
    List<Participant> participants,
    GameResult result) {
  public Match {
    Objects.requireNonNull(matchId);
    Objects.requireNonNull(roomId);
    Objects.requireNonNull(gameKey);
    Objects.requireNonNull(status);
    participants = List.copyOf(participants);
    if (stateVersion < 0 || gameEventSeq < 0) {
      throw new IllegalArgumentException("version");
    }
  }
}
