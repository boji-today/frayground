package io.frayground.domain.action;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One decision attempt by one participant during a {@link io.frayground.domain.match.TurnContext}.
 *
 * <p>One turn may hold a decision for every required participant. A failed
 * retry receives a new {@code decisionId} and still shares {@code turnId}.
 * Starting the decision fixes {@code stateVersion}, the
 * observation, {@code promptVersionId}, {@code configVersionId}, and
 * {@code deadlineAt}. {@code COMPLETED}, {@code FAILED}, {@code EXPIRED}, and
 * {@code CANCELLED} are terminal and do not return to {@code RUNNING}.
 * Cancelling a decision does not finish the match. A late response may be
 * audited and does not change the recorded outcome. {@code requestJson} is the
 * fixed model input; {@code errorCode} is set only when the attempt fails.
 */
public record Decision(
    UUID decisionId,
    UUID matchId,
    UUID turnId,
    UUID participantId,
    DecisionStatus status,
    long stateVersion,
    long observationVersion,
    UUID promptVersionId,
    UUID configVersionId,
    String requestJson,
    Instant startedAt,
    Instant deadlineAt,
    Instant endedAt,
    String errorCode) {
  public Decision {
    Objects.requireNonNull(decisionId);
    Objects.requireNonNull(matchId);
    Objects.requireNonNull(turnId);
    Objects.requireNonNull(participantId);
    Objects.requireNonNull(status);
    if (stateVersion < 0 || observationVersion < 0) {
      throw new IllegalArgumentException("version");
    }
    if (errorCode != null && errorCode.isBlank()) {
      throw new IllegalArgumentException("errorCode");
    }
  }
}
