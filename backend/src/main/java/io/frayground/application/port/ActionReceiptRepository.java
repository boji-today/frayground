package io.frayground.application.port;

import io.frayground.domain.action.ActionEnvelope;
import io.frayground.domain.action.ActionSubmissionResult;
import java.util.Optional;
import java.util.UUID;

public interface ActionReceiptRepository {
  Optional<ActionSubmissionResult> findByActionId(UUID actionId);

  void saveFinal(ActionEnvelope envelope, ActionSubmissionResult result);
}
