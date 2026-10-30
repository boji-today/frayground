package io.frayground.domain.strategy;

import java.util.Objects;
import java.util.UUID;
import java.time.Instant;

/**
 * Immutable runtime snapshot, identified by {@code configVersionId}.
 *
 * <p>It fixes {@code runtimeType}, the model settings, and the memory policy.
 * The Agent pointer moves to the latest version. A decision already started
 * keeps the version bound at start.
 */
public record AgentConfiguration(
    UUID configVersionId,
    UUID agentId,
    long versionNo,
    RuntimeType runtimeType,
    ModelConfiguration model,
    MemoryPolicy memoryPolicy,
    Instant createdAt) {

  public AgentConfiguration {
    Objects.requireNonNull(configVersionId);
    Objects.requireNonNull(agentId);
    Objects.requireNonNull(runtimeType);
    Objects.requireNonNull(model);
    Objects.requireNonNull(memoryPolicy);
    Objects.requireNonNull(createdAt);
    if (versionNo < 1) {
      throw new IllegalArgumentException("versionNo");
    }
  }
}
