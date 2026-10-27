package io.frayground.domain.game;

/**
 * Authoritative rules state of one match.
 *
 * <p>A concrete game supplies the state. Callers treat it as immutable, and it
 * is not a view every agent may read. Persistence identifies a state by match
 * and {@code stateVersion}. The initial version is 0, and only a successful
 * rules settlement advances it, once for the whole phase.
 */
public interface GameState {}
