package io.frayground.application.port;

import io.frayground.domain.action.ActionEnvelope;
import java.util.Optional;
import java.util.UUID;

/** One durable runtime invocation claim and its eventual submission envelope per decision. */
public interface DecisionExecutionClaimStore {
  boolean claim(UUID decisionId);
  void saveEnvelope(ActionEnvelope envelope);
  Optional<ActionEnvelope> findEnvelope(UUID decisionId);
}
