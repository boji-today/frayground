package io.frayground.agent;

/**
 * Failure from a runtime or a model call.
 *
 * <p>{@code code} is the stable error code. {@code message} is diagnostic
 * text and is not an action.
 */
public record RuntimeError(String code, String message) {
  public RuntimeError {
    if (code == null || code.isBlank() || message == null) {
      throw new IllegalArgumentException("error");
    }
  }
}
