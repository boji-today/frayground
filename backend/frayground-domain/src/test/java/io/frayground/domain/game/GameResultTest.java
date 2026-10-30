package io.frayground.domain.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GameResultTest {

  private final UUID winner = UUID.randomUUID();
  private final UUID loser = UUID.randomUUID();

  @Test
  void winRequiresRankOne() {
    GameResult result = new GameResult(
        GameOutcome.WIN,
        List.of(new ParticipantStanding(winner, 1, 1.0), new ParticipantStanding(loser, 2, 0.0)),
        "line");

    assertThat(result.standings()).extracting(ParticipantStanding::rank).containsExactly(1, 2);
    assertThatThrownBy(() -> new GameResult(
            GameOutcome.WIN, List.of(new ParticipantStanding(loser, 2, null)), null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void ongoingAndDrawMayHaveNoWinner() {
    assertThat(new GameResult(GameOutcome.ONGOING, List.of(), null).status())
        .isEqualTo(GameOutcome.ONGOING);
    assertThat(new GameResult(
            GameOutcome.DRAW, List.of(new ParticipantStanding(winner, 2, null)), "full")
        .status())
        .isEqualTo(GameOutcome.DRAW);
  }

  @Test
  void rejectsDuplicateParticipants() {
    assertThatThrownBy(() -> new GameResult(
            GameOutcome.DRAW,
            List.of(new ParticipantStanding(winner, 1, null), new ParticipantStanding(winner, 1, null)),
            null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void copiesStandings() {
    List<ParticipantStanding> standings = new ArrayList<>();
    standings.add(new ParticipantStanding(winner, 1, null));

    GameResult result = new GameResult(GameOutcome.WIN, standings, null);
    standings.clear();

    assertThat(result.standings()).hasSize(1);
  }
}
