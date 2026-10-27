package io.frayground.domain.game;

/**
 * Legal projection of the rules state for one seat.
 *
 * <p>It is not the authoritative {@link GameState}. In the current scope the
 * observation version equals {@code stateVersion}.
 */
public interface GameObservation {}
