package io.frayground.domain.game;

import java.util.List;
import java.util.Objects;

public record Resolution<S extends GameState>(
    S newState, List<GameEventDraft> events, GameResult result) {

  public Resolution {
    Objects.requireNonNull(newState);
    events = List.copyOf(events);
    Objects.requireNonNull(result);
  }
}
