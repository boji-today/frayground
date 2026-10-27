package io.frayground.domain.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ControlCommandTest {

  private final UUID commandId = UUID.randomUUID();
  private final UUID matchId = UUID.randomUUID();

  @Test
  void setModeRequiresMode() {
    ControlCommand command = command(ControlCommandType.SET_MODE, ExecutionMode.STEP);

    assertThat(command.mode()).isEqualTo(ExecutionMode.STEP);
    assertThatThrownBy(() -> command(ControlCommandType.SET_MODE, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void otherTypesRejectMode() {
    ControlCommand command = command(ControlCommandType.STEP, null);

    assertThat(command.mode()).isNull();
    assertThat(command.expectedRoomRevision()).isZero();
    assertThatThrownBy(() -> command(ControlCommandType.PAUSE, ExecutionMode.AUTO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> command(ControlCommandType.RESET, ExecutionMode.STEP))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNegativeRevision() {
    assertThatThrownBy(() -> new ControlCommand(
            commandId, -1, matchId, ControlCommandType.START, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private ControlCommand command(ControlCommandType type, ExecutionMode mode) {
    return new ControlCommand(commandId, 0, matchId, type, mode);
  }
}
