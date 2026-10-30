package io.frayground.domain.action;

import java.util.Objects;
import java.util.UUID;
import java.util.Map;

/**
 * One action submission, identified by {@code actionId}.
 *
 * <p>It carries identity, the base {@code stateVersion}, and the action
 * payload. It does not define the effect and is not proof of success. Passing
 * and resigning are payload kinds when the game defines them.
 * {@code protocolVersion} is {@code 1.0}. {@code action} is a map so this
 * record does not depend on a JSON library.
 */
public record ActionEnvelope(
    String protocolVersion,
    UUID matchId,
    UUID participantId,
    UUID turnId,
    UUID decisionId,
    UUID actionId,
    long stateVersion,
    Map<String, Object> action) {

  public ActionEnvelope {
    if (!"1.0".equals(protocolVersion)) {
      throw new IllegalArgumentException("protocolVersion");
    }
    Objects.requireNonNull(matchId);
    Objects.requireNonNull(participantId);
    Objects.requireNonNull(turnId);
    Objects.requireNonNull(decisionId);
    Objects.requireNonNull(actionId);
    if (stateVersion < 0 || action == null) {
      throw new IllegalArgumentException("action");
    }
    action = Map.copyOf(action);
  }
}
