package io.frayground.domain.action;

import io.frayground.agent.TokenUsage;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Private audit of one decision, identified by {@code decisionId}.
 *
 * <p>JSON fields are strings so this record does not depend on a JSON
 * library. {@code submissionResult} is the authoritative receipt.
 * {@code usage} is null when the provider omitted it. Later diagnostics do
 * not change the decision's terminal state or the original receipt.
 */
public record DecisionRecord(
    UUID decisionId,
    String contextBuilderVersion,
    String modelInputJson,
    String modelOutputJson,
    String parsedActionJson,
    String validationResultJson,
    ActionSubmissionResult submissionResult,
    String errorJson,
    TokenUsage usage,
    Duration latency,
    String diagnosticsJson) {
  public DecisionRecord {
    Objects.requireNonNull(decisionId);
    if (contextBuilderVersion != null && contextBuilderVersion.isBlank()) {
      throw new IllegalArgumentException("contextBuilderVersion");
    }
    if (latency != null && latency.isNegative()) {
      throw new IllegalArgumentException("latency");
    }
  }
}
