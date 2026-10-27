package io.frayground.engine;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Facts fixed when a decision becomes running.
 *
 * <p>Prompt and configuration are bound by version id. Observation and the
 * action specification are JSON strings so this record does not depend on a
 * JSON library. {@code deadline} is the server instant after which the
 * decision expires.
 */
public record StartedDecision(
    UUID roomId,
    UUID matchId,
    UUID decisionId,
    UUID participantId,
    UUID turnId,
    UUID promptVersionId,
    UUID configVersionId,
    long stateVersion,
    long observationVersion,
    String observationJson,
    String actionSpecificationJson,
    Instant deadline) {

  public StartedDecision {
    Objects.requireNonNull(roomId);
    Objects.requireNonNull(matchId);
    Objects.requireNonNull(decisionId);
    Objects.requireNonNull(participantId);
    Objects.requireNonNull(turnId);
    Objects.requireNonNull(promptVersionId);
    Objects.requireNonNull(configVersionId);
    Objects.requireNonNull(observationJson);
    Objects.requireNonNull(actionSpecificationJson);
    Objects.requireNonNull(deadline);
    if (stateVersion < 0 || observationVersion < 0) {
      throw new IllegalArgumentException("stateVersion");
    }
  }
}
