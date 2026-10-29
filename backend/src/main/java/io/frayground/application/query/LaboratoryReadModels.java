package io.frayground.application.query;

import io.frayground.domain.action.*;
import io.frayground.domain.game.*;
import io.frayground.domain.match.*;
import java.util.List;

/** Read-only data collected while a single game transaction is active. */
public final class LaboratoryReadModels {
  private LaboratoryReadModels() {}
  public record DecisionData(Decision decision, DecisionRecord record, ActionEnvelope submitted) {}
  public record MatchData(Match match, TurnContext turn, StateSnapshot snapshot,
      GameEvent lastMove, List<DecisionData> currentDecisions) {
    public MatchData { currentDecisions = List.copyOf(currentDecisions); }
  }
  public record RoomData(Room room, MatchData currentMatch) {}
  public record Page<T>(List<T> items, String nextCursor) {
    public Page { items = List.copyOf(items); }
  }
  public record EventPage(List<GameEvent> items, long nextAfter, boolean hasMore) {
    public EventPage { items = List.copyOf(items); }
  }
}
