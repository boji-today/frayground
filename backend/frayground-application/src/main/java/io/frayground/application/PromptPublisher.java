package io.frayground.application;

import io.frayground.domain.strategy.PromptVersion;
import java.util.UUID;

/**
 * Publishes the current draft as an immutable prompt version.
 *
 * <p>The new version applies to the next decision that has not started. It
 * does not change a decision already running. A repeated {@code commandId}
 * returns the original version.
 */
public interface PromptPublisher {
  PromptVersion publish(UUID roomId, UUID agentId, PublishPrompt command);
}
