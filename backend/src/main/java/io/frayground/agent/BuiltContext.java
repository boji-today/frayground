package io.frayground.agent;

import java.util.List;

/**
 * Messages actually prepared for one model call.
 *
 * <p>{@code builderVersion} identifies how the messages were constructed.
 * {@code utf8Bytes} is the encoded size of those messages.
 */
public record BuiltContext(String builderVersion, List<ModelMessage> messages, int utf8Bytes) {
  public BuiltContext {
    if (builderVersion == null || builderVersion.isBlank() || utf8Bytes < 0) {
      throw new IllegalArgumentException("context");
    }
    messages = List.copyOf(messages);
  }
}
