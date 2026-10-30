package io.frayground.engine;

import java.util.UUID;

/**
 * Opens and closes a decision inside the room lock.
 *
 * <p>{@code start} binds the prompt version, configuration version,
 * observation, and deadline, then returns that fixed {@link StartedDecision}.
 * {@code fail}, {@code expire}, and {@code cancel} end a decision without
 * advancing {@code stateVersion}. {@code cancel} covers every unfinished
 * decision in the room's current turn.
 */
public interface DecisionLifecycle {
  StartedDecision start(UUID roomId);

  void fail(UUID decisionId, String errorCode);

  void expire(UUID decisionId);

  void cancel(UUID roomId, UUID commandId);
}
