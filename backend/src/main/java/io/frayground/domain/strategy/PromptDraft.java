package io.frayground.domain.strategy;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Mutable prompt text for one Agent.
 *
 * <p>Saving a draft increments {@code revision} and does not change the
 * published prompt. A decision binds a {@code PromptVersion}, not this draft.
 */
public record PromptDraft(
    UUID agentId, long revision, String content, Instant updatedAt) {
  public PromptDraft {
    Objects.requireNonNull(agentId);
    Objects.requireNonNull(content);
    Objects.requireNonNull(updatedAt);
    if (revision < 1) {
      throw new IllegalArgumentException("revision");
    }
  }
}
