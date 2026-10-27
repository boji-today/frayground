package io.frayground.domain.match;

/**
 * Lifecycle of one action phase.
 *
 * <p>{@code OPEN} is collecting the decisions required for the phase.
 * {@code RESOLVED} follows one successful settlement and is what advances the
 * match state version, once for the whole phase. {@code CANCELLED} follows
 * reset or an abort of the open phase and does not advance that version.
 * Passing or resigning is not a status here: when the game accepts either
 * action, settlement ends the phase as {@code RESOLVED}. Both
 * {@code RESOLVED} and {@code CANCELLED} are terminal.
 */
public enum TurnStatus {
  OPEN,
  RESOLVED,
  CANCELLED
}
