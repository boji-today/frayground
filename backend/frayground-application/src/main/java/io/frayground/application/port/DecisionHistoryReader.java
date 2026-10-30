package io.frayground.application.port;

import io.frayground.domain.action.Decision;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Stable keyset reads of all attempts in one match. Null timestamps sort first. */
public interface DecisionHistoryReader {
  record Position(Instant startedAt, UUID decisionId) {}
  List<Decision> readAfter(UUID matchId, Position position, int limit);
  boolean belongsToMatch(UUID turnId, UUID matchId);
}
