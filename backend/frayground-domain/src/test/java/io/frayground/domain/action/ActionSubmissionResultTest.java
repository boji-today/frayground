package io.frayground.domain.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ActionSubmissionResultTest {

  private final UUID actionId = UUID.randomUUID();
  private final UUID decisionId = UUID.randomUUID();
  private final UUID matchId = UUID.randomUUID();
  private final Map<String, Object> played = Map.of("column", 2);

  @Test
  void acceptsExecutedReceipt() {
    ActionSubmissionResult result = result(SubmissionStatus.EXECUTED, 4, played, null);

    assertThat(result.executedAction()).isEqualTo(played);
    assertThat(result.errorCode()).isNull();
  }

  @Test
  void acceptsRejectedReceipt() {
    ActionSubmissionResult result = result(SubmissionStatus.REJECTED, 4, null, "COLUMN_FULL");

    assertThat(result.executedAction()).isNull();
    assertThat(result.errorCode()).isEqualTo("COLUMN_FULL");
  }

  @Test
  void acceptsAcceptedReceipt() {
    ActionSubmissionResult result = result(SubmissionStatus.ACCEPTED, 0, null, null);

    assertThat(result.executedAction()).isNull();
    assertThat(result.errorCode()).isNull();
  }

  @Test
  void rejectsMismatchedPayload() {
    assertThatThrownBy(() -> result(SubmissionStatus.EXECUTED, 1, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> result(SubmissionStatus.EXECUTED, 1, played, "COLUMN_FULL"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> result(SubmissionStatus.REJECTED, 1, played, "COLUMN_FULL"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> result(SubmissionStatus.REJECTED, 1, null, " "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> result(SubmissionStatus.ACCEPTED, 1, played, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> result(SubmissionStatus.ACCEPTED, 1, null, "COLUMN_FULL"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNegativeVersion() {
    assertThatThrownBy(() -> result(SubmissionStatus.ACCEPTED, -1, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private ActionSubmissionResult result(
      SubmissionStatus status,
      long version,
      Map<String, Object> executedAction,
      String errorCode) {
    return new ActionSubmissionResult(
        status, actionId, decisionId, matchId, version, executedAction, errorCode);
  }
}
