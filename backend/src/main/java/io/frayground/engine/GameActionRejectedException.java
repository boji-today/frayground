package io.frayground.engine;

/** A game rule rejected an action before phase resolution. */
public final class GameActionRejectedException extends RuntimeException {
  private final String code;

  public GameActionRejectedException(String code) {
    super(code);
    if (code == null || code.isBlank()) throw new IllegalArgumentException("code");
    this.code = code;
  }

  public String code() { return code; }
}
