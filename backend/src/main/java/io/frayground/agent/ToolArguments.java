package io.frayground.agent;

/**
 * One tool-call fragment returned by a model.
 *
 * <p>{@code argumentsJson} is the raw argument text. The platform does not
 * execute it as an arbitrary tool.
 */
public record ToolArguments(String name, String argumentsJson) {
  public ToolArguments {
    if (argumentsJson == null || argumentsJson.isBlank()) {
      throw new IllegalArgumentException("argumentsJson");
    }
    if (name != null && name.isBlank()) {
      throw new IllegalArgumentException("name");
    }
  }
}
