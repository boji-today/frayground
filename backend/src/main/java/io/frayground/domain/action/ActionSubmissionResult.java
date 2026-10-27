package io.frayground.domain.action;

import java.util.Objects;
import java.util.UUID;
import java.util.Map;

/**
 * Persisted receipt for one {@code actionId}.
 *
 * <p>{@code EXECUTED} includes {@code executedAction} and no error code.
 * {@code REJECTED} includes {@code errorCode} and no executed action.
 * {@code ACCEPTED} includes neither and does not mean the world changed.
 * {@code stateVersion} is the settled version after execution, and the version
 * that was checked when the action is rejected or only accepted.
 * {@code errorCode} is the submission code; the HTTP error object is assembled
 * at the API boundary.
 */
public record ActionSubmissionResult(
    SubmissionStatus status,
    UUID actionId,
    UUID decisionId,
    UUID matchId,
    long stateVersion,
    Map<String, Object> executedAction,
    String errorCode) {

  public ActionSubmissionResult {
    Objects.requireNonNull(status);
    Objects.requireNonNull(actionId);
    Objects.requireNonNull(decisionId);
    Objects.requireNonNull(matchId);
    if (stateVersion < 0) {
      throw new IllegalArgumentException("stateVersion");
    }
    switch (status) {
      case EXECUTED -> {
        if (executedAction == null || errorCode != null) {
          throw new IllegalArgumentException("executed");
        }
      }
      case REJECTED -> {
        if (executedAction != null || errorCode == null || errorCode.isBlank()) {
          throw new IllegalArgumentException("rejected");
        }
      }
      case ACCEPTED -> {
        if (executedAction != null || errorCode != null) {
          throw new IllegalArgumentException("accepted");
        }
      }
    }
    if (executedAction != null) {
      executedAction = Map.copyOf(executedAction);
    }
  }
}
