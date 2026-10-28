package io.frayground.adapter.model.deepseek;

import java.util.List;

/**
 * DeepSeek chat completion response body.
 *
 * <p>Unknown fields, including reasoning content, are ignored.
 */
public record DeepSeekChatResponse(String id, String model, List<Choice> choices, Usage usage) {

  public record Choice(Message message, String finishReason) {}

  public record Message(String content, String refusal, List<ToolCall> toolCalls) {}

  public record ToolCall(Function function) {}

  public record Function(String name, String arguments) {}

  public record Usage(
      Long promptTokens,
      Long completionTokens,
      Long promptCacheHitTokens,
      Long promptCacheMissTokens) {}
}
