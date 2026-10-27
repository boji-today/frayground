package io.frayground.agent;

import io.frayground.domain.strategy.AgentConfiguration;
import io.frayground.domain.strategy.PromptVersion;

/**
 * Builds the model messages for one fixed decision.
 *
 * <p>The request, prompt, and configuration are the versions already bound.
 * The builder does not read the Agent's latest pointers.
 */
public interface AgentContextBuilder {
  BuiltContext build(
      DecisionRequest request, PromptVersion prompt, AgentConfiguration configuration);
}
