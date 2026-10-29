package io.frayground.application.port;

import io.frayground.domain.game.*;
import java.util.*;

/** Game-specific conversion and rules operations at the application boundary. */
public interface DecisionGameAccess {
  Prepared prepare(GameKey key, long stateVersion, Map<String, Object> state, List<Participant> participants, UUID participantId);
  Applied apply(GameKey key, long stateVersion, Map<String, Object> state, List<Participant> participants, UUID participantId, Map<String, Object> action);
  long roundNo(GameKey key, long turnNo);

  record Prepared(TurnPlan plan, String observationJson, String actionSpecificationJson,
      String schemaId, String schemaJson, String rulesText) {}
  record Applied(Map<String, Object> state, Map<String, Object> action,
      List<GameEventDraft> events, GameResult result, TurnPlan nextPlan) {}
}
