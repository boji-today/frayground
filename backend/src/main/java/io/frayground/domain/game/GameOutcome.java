package io.frayground.domain.game;

/**
 * Rules outcome carried by a {@link GameResult}.
 *
 * <p>{@code ONGOING} means the match has not reached a rules ending.
 * {@code WIN} requires at least one rank-1 standing. {@code DRAW} does not
 * require a winner. A resignation is written by the rules as one of these
 * outcomes, usually {@code WIN}, and does not add another value. Aborting a
 * match is a {@code MatchStatus}, not an outcome here.
 */
public enum GameOutcome {
  ONGOING,
  WIN,
  DRAW
}
