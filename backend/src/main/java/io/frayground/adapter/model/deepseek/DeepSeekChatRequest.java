package io.frayground.adapter.model.deepseek;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * DeepSeek chat completion request body.
 */
public record DeepSeekChatRequest(
    String model,
    List<Message> messages,
    double temperature,
    int maxTokens,
    boolean stream,
    Thinking thinking,
    ResponseFormat responseFormat,
    List<Tool> tools,
    ToolChoice toolChoice) {

  public record Message(String role, String content) {}

  public record Thinking(String type) {}

  public record ResponseFormat(String type) {}

  public record Tool(String type, Function function) {}

  public record Function(String name, boolean strict, JsonNode parameters) {}

  public record ToolChoice(String type, NamedFunction function) {}

  public record NamedFunction(String name) {}
}
