package io.frayground.domain.game;

/**
 * Who must act in one phase.
 *
 * <p>{@code TURN_BASED_SEQUENTIAL} lists exactly one required participant.
 * {@code TURN_BASED_SIMULTANEOUS} lists every seat that must act in the phase.
 * Those decisions share one turn. The mode does not open more than one current
 * turn on a match.
 */
public enum SchedulingMode {
  TURN_BASED_SEQUENTIAL,
  TURN_BASED_SIMULTANEOUS
}
