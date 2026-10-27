package io.frayground.application;

import java.util.Objects;
import java.util.UUID;

/**
 * Command to publish one agent's current draft.
 *
 * <p>The body names the draft revision to freeze, not a second copy of the
 * prompt text. {@code expectedRoomRevision} and {@code expectedMatchId} are
 * checked after the command receipt lookup. {@code expectedDraftRevision}
 * starts at 1.
 */
public record PublishPrompt(
    UUID commandId,
    long expectedRoomRevision,
    UUID expectedMatchId,
    long expectedDraftRevision) {

  public PublishPrompt {
    Objects.requireNonNull(commandId);
    Objects.requireNonNull(expectedMatchId);
    if (expectedRoomRevision < 0 || expectedDraftRevision < 1) {
      throw new IllegalArgumentException("revision");
    }
  }
}
