package io.frayground.domain.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.frayground.domain.game.SchedulingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TurnContextTest {

  private final UUID turnId = UUID.randomUUID();
  private final UUID seat = UUID.randomUUID();

  @Test
  void acceptsSequentialPhase() {
    TurnContext turn = turn(SchedulingMode.TURN_BASED_SEQUENTIAL, List.of(seat), 1, 1, 0);

    assertThat(turn.requiredParticipants()).containsExactly(seat);
    assertThat(turn.status()).isEqualTo(TurnStatus.OPEN);
  }

  @Test
  void acceptsSimultaneousPhase() {
    UUID other = UUID.randomUUID();

    TurnContext turn = turn(
        SchedulingMode.TURN_BASED_SIMULTANEOUS, List.of(seat, other), 2, 3, 4);

    assertThat(turn.requiredParticipants()).containsExactly(seat, other);
  }

  @Test
  void copiesRequiredParticipants() {
    List<UUID> seats = new ArrayList<>();
    seats.add(seat);

    TurnContext turn = turn(SchedulingMode.TURN_BASED_SEQUENTIAL, seats, 1, 1, 0);
    seats.clear();

    assertThat(turn.requiredParticipants()).containsExactly(seat);
  }

  @Test
  void rejectsNonPositiveNumbers() {
    assertThatThrownBy(() -> turn(SchedulingMode.TURN_BASED_SEQUENTIAL, List.of(seat), 0, 1, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> turn(SchedulingMode.TURN_BASED_SEQUENTIAL, List.of(seat), 1, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> turn(SchedulingMode.TURN_BASED_SEQUENTIAL, List.of(seat), 1, 1, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsWrongSeatCount() {
    assertThatThrownBy(() -> turn(SchedulingMode.TURN_BASED_SEQUENTIAL, List.of(), 1, 1, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> turn(
            SchedulingMode.TURN_BASED_SEQUENTIAL, List.of(seat, UUID.randomUUID()), 1, 1, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> turn(SchedulingMode.TURN_BASED_SIMULTANEOUS, List.of(), 1, 1, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNullOrDuplicateSeats() {
    List<UUID> withNull = new ArrayList<>();
    withNull.add(null);
    assertThatThrownBy(() -> turn(SchedulingMode.TURN_BASED_SEQUENTIAL, withNull, 1, 1, 0))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> turn(
            SchedulingMode.TURN_BASED_SIMULTANEOUS, List.of(seat, seat), 1, 1, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private TurnContext turn(
      SchedulingMode mode, List<UUID> seats, long turnNo, long roundNo, long version) {
    return new TurnContext(turnId, turnNo, roundNo, version, mode, seats, TurnStatus.OPEN);
  }
}
