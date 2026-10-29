package io.frayground.config;

import io.frayground.domain.strategy.RuntimeType;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Startup-only bindings for the two laboratory seats. */
@ConfigurationProperties("frayground.laboratory.agents")
public record LaboratoryAgentProperties(Agent a, Agent b) {
  public LaboratoryAgentProperties {
    if (a == null || b == null) throw new IllegalArgumentException("laboratory agents a and b");
  }

  public record Agent(RuntimeType runtime, String prompt, String provider, String modelId,
      boolean thinking, double temperature, int maxOutputTokens, int decisionTimeoutMs,
      String outputStrategy, boolean allowBeta) {
    public Agent {
      if (runtime == null) throw new IllegalArgumentException("runtime");
      if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("prompt");
      if (provider == null || provider.isBlank()) throw new IllegalArgumentException("provider");
      if (modelId == null || modelId.isBlank()) throw new IllegalArgumentException("modelId");
      if (outputStrategy == null || outputStrategy.isBlank()) throw new IllegalArgumentException("outputStrategy");
      if (maxOutputTokens < 1 || decisionTimeoutMs < 1 || Double.isNaN(temperature) || temperature < 0)
        throw new IllegalArgumentException("model settings");
      if (runtime == RuntimeType.LLM && !("JSON_MODE".equals(outputStrategy)
          || "STRICT_TOOL".equals(outputStrategy) && allowBeta)) throw new IllegalArgumentException("outputStrategy");
    }
  }
}
