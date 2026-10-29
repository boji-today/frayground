package io.frayground.config;

import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Fixed identities for the single in-memory laboratory. */
@ConfigurationProperties("frayground.laboratory")
public record LaboratoryProperties(UUID roomId, UUID agentA, UUID agentB) {
  public LaboratoryProperties {
    if (roomId == null || agentA == null || agentB == null || agentA.equals(agentB))
      throw new IllegalArgumentException("laboratory identities");
  }
}
