package io.frayground.application.port;

import io.frayground.agent.HostedBindings;
import java.util.Optional;
import java.util.UUID;

/** Read published bindings, including immutable versions fixed by a decision. */
public interface AgentBindingsReader {
  Optional<HostedBindings> current(UUID agentId);
  Optional<HostedBindings> versions(UUID agentId, UUID promptVersionId, UUID configVersionId);
}
