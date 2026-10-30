package io.frayground.agent;

/**
 * One role and text segment sent toward a model.
 *
 * <p>The adapter turns it into vendor JSON. It is not a provider SDK type.
 */
public record ModelMessage(String role, String text) {
  public ModelMessage {
    if (role == null || role.isBlank() || text == null) {
      throw new IllegalArgumentException("message");
    }
  }
}
