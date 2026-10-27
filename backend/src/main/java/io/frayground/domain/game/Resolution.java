package io.frayground.domain.game;

import java.util.List;
import java.util.Objects;

/**
 * Rules output of one phase settlement.
 *
 * <p>{@code newState} is the next authoritative state. {@code events} are
 * facts produced by the rules and do not yet carry match identity or
 * sequence. {@code result} is the rules outcome after this phase, including
 * an ongoing match. Applying the resolution advances {@code stateVersion}
 * once for the whole phase.
 */
public record Resolution<S extends GameState>(
    S newState, List<GameEventDraft> events, GameResult result) {

  public Resolution {
    Objects.requireNonNull(newState);
    events = List.copyOf(events);
    Objects.requireNonNull(result);
  }
}
