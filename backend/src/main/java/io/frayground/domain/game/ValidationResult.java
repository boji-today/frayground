package io.frayground.domain.game;

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
