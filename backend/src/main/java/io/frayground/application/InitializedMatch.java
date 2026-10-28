package io.frayground.application;

import io.frayground.domain.game.StateSnapshot;
import io.frayground.domain.match.Match;
import io.frayground.domain.match.TurnContext;
import java.util.Objects;

/** Initial match values produced together before persistence or execution. */
public record InitializedMatch(Match match, TurnContext firstTurn, StateSnapshot initialSnapshot) {
  public InitializedMatch {
    Objects.requireNonNull(match);
    Objects.requireNonNull(firstTurn);
    Objects.requireNonNull(initialSnapshot);
  }
}
