package io.frayground.domain.match;

/**
 * Lifecycle of one action phase.
 *
 * <p>{@code OPEN} is collecting the decisions required for the phase.
 * {@code RESOLVED} follows one successful settlement and is what advances the
 * match state version, once for the whole phase. {@code CANCELLED} follows
 * reset or an abort of the open phase. Both {@code RESOLVED} and
 * {@code CANCELLED} are terminal.
 */
public enum TurnStatus {
  OPEN,
  RESOLVED,
  CANCELLED
}
