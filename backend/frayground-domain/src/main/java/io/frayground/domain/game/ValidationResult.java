package io.frayground.domain.game;

/**
 * Rules check of one action before settlement.
 *
 * <p>{@link Accepted} means the action is legal in the current state.
 * {@link Rejected} carries a rules code. Neither value changes the match.
 */
public sealed interface ValidationResult
    permits ValidationResult.Accepted, ValidationResult.Rejected {

  record Accepted() implements ValidationResult {}

  record Rejected(String code) implements ValidationResult {
    public Rejected {
      if (code == null || code.isBlank()) {
        throw new IllegalArgumentException("code");
      }
    }
  }
}
