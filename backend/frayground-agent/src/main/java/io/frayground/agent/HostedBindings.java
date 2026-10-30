package io.frayground.agent;

import io.frayground.domain.strategy.AgentConfiguration;
import io.frayground.domain.strategy.PromptVersion;
import java.util.Objects;

/**
 * Prompt and configuration bound to one decision.
 *
 * <p>These are the versions fixed at start. They are not the Agent's latest
 * pointers.
 */
public record HostedBindings(PromptVersion prompt, AgentConfiguration configuration) {
  public HostedBindings {
    Objects.requireNonNull(prompt);
    Objects.requireNonNull(configuration);
  }
}
