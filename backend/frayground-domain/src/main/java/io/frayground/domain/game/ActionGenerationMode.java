package io.frayground.domain.game;

/**
 * How a model is asked to produce an action.
 *
 * <p>{@code ACTION_SELECTION} returns a {@code selectedActionId} looked up in
 * this decision's immutable options. {@code STRUCTURED_ACTION} returns a
 * game-specific action that still passes schema parsing and
 * {@link GameDefinition#validate}. Choosing a listed option does not skip
 * that check. The local id is not a submission {@code actionId}.
 */
public enum ActionGenerationMode {
  ACTION_SELECTION,
  STRUCTURED_ACTION
}
