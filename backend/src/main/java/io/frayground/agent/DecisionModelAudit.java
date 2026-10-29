package io.frayground.agent;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Narrow persistence boundary for private model-call evidence. */
public interface DecisionModelAudit {
  boolean recordInput(UUID decisionId, BuiltContext context, String schemaJson, Map<String,Object> parameters);
  void recordOutput(UUID decisionId, ModelInvocationResult result);
}
