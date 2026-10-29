package io.frayground.adapter.memory;

import io.frayground.application.port.DecisionHistoryReader;
import io.frayground.domain.action.Decision;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Reads the transaction's memory image; no second index or mutable copy. */
public final class InMemoryDecisionHistoryReader implements DecisionHistoryReader {
  private static final Comparator<Decision> ORDER = Comparator
      .comparing(Decision::startedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
      .thenComparing(Decision::decisionId);
  private final InMemoryStorage storage;
  public InMemoryDecisionHistoryReader(InMemoryStorage storage) { this.storage = storage; }
  @Override public boolean belongsToMatch(UUID turnId, UUID matchId) {
    return matchId.equals(storage.readState().turnMatches.get(turnId));
  }
  @Override public List<Decision> readAfter(UUID matchId, Position position, int limit) {
    if (matchId == null || limit < 1 || (position != null && position.decisionId() == null))
      throw new IllegalArgumentException("history cursor");
    return storage.readState().decisions.values().stream()
        .filter(d -> d.matchId().equals(matchId))
        .filter(d -> position == null || compare(d, position) > 0)
        .sorted(ORDER).limit(limit).toList();
  }
  private static int compare(Decision d, Position p) {
    int time = Comparator.nullsFirst(Comparator.<java.time.Instant>naturalOrder())
        .compare(d.startedAt(), p.startedAt());
    return time != 0 ? time : d.decisionId().compareTo(p.decisionId());
  }
}
