package io.frayground.application;

import java.util.UUID;

/**
 * Deterministic laboratory rejection.
 *
 * <p>A publish rejection is committed with its command receipt. Draft
 * conflicts have no command id. Callers map {@code code} to HTTP.
 */
public final class RejectedCommand extends RuntimeException {
  private final String code;
  private final UUID commandId;

  public RejectedCommand(String code) { this(code, null); }

  public RejectedCommand(String code, UUID commandId) {
    super(code);
    if (code == null || code.isBlank()) throw new IllegalArgumentException("code");
    this.code = code;
    this.commandId = commandId;
  }

  public String code() { return code; }
  public UUID commandId() { return commandId; }
}
