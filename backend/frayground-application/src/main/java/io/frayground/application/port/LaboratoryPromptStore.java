package io.frayground.application.port;

import io.frayground.domain.strategy.AgentConfiguration;
import io.frayground.domain.strategy.PromptDraft;
import io.frayground.domain.strategy.PromptVersion;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** In-memory draft and published prompt pointer for laboratory agents. */
public interface LaboratoryPromptStore {
  Optional<AgentPromptState> find(UUID agentId);

  PromptDraft saveDraft(UUID agentId, long expectedRevision, String content, Instant updatedAt);

  void commitPublished(UUID roomId, UUID commandId, PromptVersion version);

  Optional<PromptVersion> publishedForCommand(UUID roomId, UUID commandId);

  record AgentPromptState(String name, PromptDraft draft, PromptVersion published, AgentConfiguration configuration) {}
}
