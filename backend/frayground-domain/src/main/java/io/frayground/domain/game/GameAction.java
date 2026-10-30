package io.frayground.domain.game;

/**
 * Structured action defined by a game.
 *
 * <p>Passing and resigning are actions when that game defines them. The
 * envelope carries the payload; {@link GameDefinition#validate} and
 * {@link GameDefinition#resolve} decide the effect. A game that does not
 * define the action rejects it.
 */
public interface GameAction {}
