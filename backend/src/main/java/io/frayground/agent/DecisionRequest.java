package io.frayground.agent;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Public observation contract for one decision.
 *
 * <p>{@code protocolVersion} is {@code 1.0}. Schema, specification, and
 * observation are JSON strings so this record does not depend on a JSON
 * library. {@code agentVersion} may be absent. The prompt and credentials are
 * not part of this request.
 */
public record DecisionRequest(
    String protocolVersion,
    UUID decisionId,
    UUID matchId,
    UUID participantId,
    UUID turnId,
    long stateVersion,
    long observationVersion,
    String gameType,
    String gameRulesVersion,
    String actionSchemaId,
    String actionSchemaJson,
    String actionSpecificationJson,
    String rulesText,
    String observationJson,
    Instant deadlineAt,
    String agentVersion) {

  public DecisionRequest {
    if (!"1.0".equals(protocolVersion)) {
      throw new IllegalArgumentException("protocolVersion");
    }
    Objects.requireNonNull(decisionId);
    Objects.requireNonNull(matchId);
    Objects.requireNonNull(participantId);
    Objects.requireNonNull(turnId);
    requireText(gameType, "gameType");
    requireText(gameRulesVersion, "gameRulesVersion");
    requireText(actionSchemaId, "actionSchemaId");
    requireText(actionSchemaJson, "actionSchemaJson");
    requireText(actionSpecificationJson, "actionSpecificationJson");
    Objects.requireNonNull(rulesText);
    requireText(observationJson, "observationJson");
    Objects.requireNonNull(deadlineAt);
    if (stateVersion < 0 || observationVersion < 0) {
      throw new IllegalArgumentException("version");
    }
    if (agentVersion != null && agentVersion.isBlank()) {
      throw new IllegalArgumentException("agentVersion");
    }
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name);
    }
  }
}
