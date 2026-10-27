package io.frayground.application.port;

import io.frayground.domain.action.DecisionStatus;
import java.util.UUID;

public interface DecisionRepository {
  boolean transition(UUID decisionId, DecisionStatus from, DecisionStatus to);
}
