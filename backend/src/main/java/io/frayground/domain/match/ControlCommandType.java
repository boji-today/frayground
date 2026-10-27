package io.frayground.domain.match;

/**
 * Room control requested against the current match.
 *
 * <p>{@code START}, {@code CONTINUE}, and {@code STEP} grant execution.
 * {@code PAUSE} asks the room to stop without cancelling an in-flight
 * decision. {@code SET_MODE} changes {@link ExecutionMode} and carries a mode
 * only for this type. {@code CANCEL_DECISION} cancels every unfinished
 * decision in the current turn and does not end the match. {@code RESET}
 * aborts an unfinished match and opens a new {@code READY} match. After
 * {@code FINISHED} or {@code ABORTED}, only reset is accepted.
 */
public enum ControlCommandType {
  START,
  PAUSE,
  CONTINUE,
  STEP,
  SET_MODE,
  CANCEL_DECISION,
  RESET
}
