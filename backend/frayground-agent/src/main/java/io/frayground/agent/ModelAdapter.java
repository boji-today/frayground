package io.frayground.agent;

import io.frayground.domain.strategy.ModelConfiguration;
import java.util.concurrent.CompletionStage;

/**
 * Vendor boundary for one model call.
 *
 * <p>{@code capabilities} reports which output modes the configuration can
 * use. {@code invoke} performs a single call and normalizes the response.
 */
public interface ModelAdapter {
  ModelCapabilities capabilities(ModelConfiguration configuration);

  CompletionStage<ModelInvocationResult> invoke(
      ModelRequest request, CancellationSignal cancellation);
}
