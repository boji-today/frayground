package io.frayground.agent;

import java.time.Duration;
import java.util.List;

/**
 * Normalized result of one model call.
 *
 * <p>{@code content} and {@code toolArguments} are the raw action output.
 * {@code usage} is null when the provider omits it. {@code error} is set when
 * the call did not produce a usable action.
 */
public record ModelInvocationResult(
    String providerRequestId,
    String actualModel,
    String content,
    List<ToolArguments> toolArguments,
    String finishReason,
    TokenUsage usage,
    Duration latency,
    RuntimeError error) {

  public ModelInvocationResult {
    toolArguments = toolArguments == null ? List.of() : List.copyOf(toolArguments);
  }
}
