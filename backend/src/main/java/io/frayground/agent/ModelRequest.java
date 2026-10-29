package io.frayground.agent;

import io.frayground.domain.strategy.ModelConfiguration;
import java.util.List;
import java.util.Objects;
import java.time.Instant;

/**
 * One model call: messages, output schema, and the bound configuration.
 *
 * <p>{@code outputSchemaJson} is a JSON string so this record does not depend
 * on a JSON library.
 */
public record ModelRequest(
    List<ModelMessage> messages, String outputSchemaJson, ModelConfiguration configuration, Instant deadlineAt) {

  public ModelRequest(List<ModelMessage> messages, String outputSchemaJson, ModelConfiguration configuration) {
    this(messages, outputSchemaJson, configuration, null);
  }

  public ModelRequest {
    messages = List.copyOf(messages);
    if (outputSchemaJson == null || outputSchemaJson.isBlank()) {
      throw new IllegalArgumentException("outputSchemaJson");
    }
    Objects.requireNonNull(configuration);
  }
}
