package io.frayground.adapter.agent;

import io.frayground.agent.HostedBindings;
import io.frayground.application.port.AgentBindingsReader;
import io.frayground.domain.strategy.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Immutable bindings for the lifetime of the memory laboratory. */
public final class FixedLaboratoryBindings implements AgentBindingsReader {
  private final Map<UUID, HostedBindings> bindings;

  public FixedLaboratoryBindings(UUID agentA, UUID agentB, Instant createdAt) {
    bindings = Map.of(agentA, create(agentA, createdAt), agentB, create(agentB, createdAt));
  }

  private static HostedBindings create(UUID id, Instant createdAt) {
    UUID promptId = UUID.nameUUIDFromBytes((id + ":prompt:1").getBytes(StandardCharsets.UTF_8));
    UUID configId = UUID.nameUUIDFromBytes((id + ":config:1").getBytes(StandardCharsets.UTF_8));
    var prompt = new PromptVersion(promptId, id, 1, 1, "Choose a legal move.", createdAt);
    var model = new ModelConfiguration("mock", "mock", false, 0, 1024, 30000, null, "JSON", false);
    var config = new AgentConfiguration(configId, id, 1, RuntimeType.MOCK, model,
        new MemoryPolicy(MemoryMode.STATELESS), createdAt);
    return new HostedBindings(prompt, config);
  }

  @Override public Optional<HostedBindings> current(UUID agentId) {
    return Optional.ofNullable(bindings.get(agentId));
  }

  @Override public Optional<HostedBindings> versions(UUID agentId, UUID promptId, UUID configId) {
    return current(agentId).filter(b -> b.prompt().promptVersionId().equals(promptId)
        && b.configuration().configVersionId().equals(configId));
  }
}
