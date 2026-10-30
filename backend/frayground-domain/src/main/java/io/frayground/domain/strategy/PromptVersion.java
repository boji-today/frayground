package io.frayground.domain.strategy;

import java.util.Objects;
import java.util.UUID;
import java.time.Instant;

/**
 * Immutable published prompt, identified by {@code promptVersionId}.
 *
 * <p>It is not bound to one match. {@code sourceDraftRevision} is the draft
 * revision that was published. A later edit of the draft does not change this
 * version. The content hash is stored with the row and is not a field here.
 */
public record PromptVersion(
    UUID promptVersionId,
    UUID agentId,
    long versionNo,
    long sourceDraftRevision,
    String content,
    Instant createdAt) {

  public PromptVersion {
    Objects.requireNonNull(promptVersionId);
    Objects.requireNonNull(agentId);
    Objects.requireNonNull(content);
    Objects.requireNonNull(createdAt);
    if (versionNo < 1 || sourceDraftRevision < 1) {
      throw new IllegalArgumentException("revision");
    }
  }
}
