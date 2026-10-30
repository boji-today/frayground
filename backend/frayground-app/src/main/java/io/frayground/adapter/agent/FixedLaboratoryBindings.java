package io.frayground.adapter.agent;

import io.frayground.agent.HostedBindings;
import io.frayground.application.RejectedCommand;
import io.frayground.application.port.AgentBindingsReader;
import io.frayground.application.port.LaboratoryPromptStore;
import io.frayground.application.query.LaboratoryQueryService.NotFound;
import io.frayground.domain.strategy.AgentConfiguration;
import io.frayground.domain.strategy.MemoryMode;
import io.frayground.domain.strategy.MemoryPolicy;
import io.frayground.domain.strategy.ModelConfiguration;
import io.frayground.domain.strategy.PromptDraft;
import io.frayground.domain.strategy.PromptVersion;
import io.frayground.domain.strategy.RuntimeType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Mutable in-memory drafts and published prompts for the laboratory process. */
public final class FixedLaboratoryBindings implements AgentBindingsReader, LaboratoryPromptStore {
  private static final long MAX_SAFE = 9007199254740991L;
  private static final int MAX_PROMPT_BYTES = 16384;
  private final Map<UUID, Slot> slots = new HashMap<>();
  private final Map<String, PromptVersion> publishedByCommand = new HashMap<>();

  public FixedLaboratoryBindings(UUID agentA, UUID agentB, Instant createdAt) {
    this(Map.of(agentA, create(agentA, createdAt), agentB, create(agentB, createdAt)),
        Map.of(agentA, "Agent A", agentB, "Agent B"));
  }

  public FixedLaboratoryBindings(Map<UUID, HostedBindings> bindings) {
    this(bindings, Map.of());
  }

  public FixedLaboratoryBindings(Map<UUID, HostedBindings> bindings, Map<UUID, String> names) {
    if (bindings == null || bindings.isEmpty()) throw new IllegalArgumentException("bindings");
    bindings.forEach((id, hosted) -> {
      if (hosted == null || !hosted.prompt().agentId().equals(id) || !hosted.configuration().agentId().equals(id))
        throw new IllegalArgumentException("bindings");
      var slot = new Slot();
      slot.name = names.getOrDefault(id, "Agent");
      slot.current = hosted.prompt();
      slot.configuration = hosted.configuration();
      slot.draft = new PromptDraft(id, hosted.prompt().sourceDraftRevision(), hosted.prompt().content(), hosted.prompt().createdAt());
      slot.versions.put(hosted.prompt().promptVersionId(), hosted.prompt());
      slots.put(id, slot);
    });
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

  @Override public synchronized Optional<HostedBindings> current(UUID agentId) {
    Slot slot = slots.get(agentId);
    return slot == null ? Optional.empty() : Optional.of(new HostedBindings(slot.current, slot.configuration));
  }

  @Override public synchronized Optional<HostedBindings> versions(UUID agentId, UUID promptId, UUID configId) {
    Slot slot = slots.get(agentId);
    if (slot == null || !slot.configuration.configVersionId().equals(configId)) return Optional.empty();
    PromptVersion prompt = slot.versions.get(promptId);
    return prompt == null ? Optional.empty() : Optional.of(new HostedBindings(prompt, slot.configuration));
  }

  @Override public synchronized Optional<AgentPromptState> find(UUID agentId) {
    Slot slot = slots.get(agentId);
    if (slot == null) return Optional.empty();
    return Optional.of(new AgentPromptState(slot.name, slot.draft, slot.current, slot.configuration));
  }

  @Override public synchronized PromptDraft saveDraft(UUID agentId, long expectedRevision, String content, Instant updatedAt) {
    Slot slot = slots.get(agentId);
    if (slot == null) throw new NotFound("AGENT_NOT_FOUND");
    if (content == null || updatedAt == null) throw new IllegalArgumentException("content");
    if (content.getBytes(StandardCharsets.UTF_8).length > MAX_PROMPT_BYTES) throw new IllegalArgumentException("content");
    if (slot.draft.revision() != expectedRevision) throw new RejectedCommand("DRAFT_REVISION_CONFLICT");
    if (expectedRevision >= MAX_SAFE) throw new IllegalArgumentException("revision");
    var next = new PromptDraft(agentId, expectedRevision + 1, content, updatedAt);
    slot.draft = next;
    return next;
  }

  @Override public synchronized void commitPublished(UUID roomId, UUID commandId, PromptVersion version) {
    Slot slot = slots.get(version.agentId());
    if (slot == null) throw new IllegalStateException("AGENT_NOT_FOUND");
    slot.versions.put(version.promptVersionId(), version);
    slot.current = version;
    publishedByCommand.put(roomId + ":" + commandId, version);
  }

  @Override public synchronized Optional<PromptVersion> publishedForCommand(UUID roomId, UUID commandId) {
    return Optional.ofNullable(publishedByCommand.get(roomId + ":" + commandId));
  }

  private static final class Slot {
    String name;
    PromptDraft draft;
    PromptVersion current;
    AgentConfiguration configuration;
    final Map<UUID, PromptVersion> versions = new LinkedHashMap<>();
  }
}
