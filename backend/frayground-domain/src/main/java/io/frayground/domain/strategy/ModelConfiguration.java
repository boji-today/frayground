package io.frayground.domain.strategy;

/**
 * Model call settings fixed in one configuration version.
 *
 * <p>{@code credentialRef} names a secret; it is not the secret itself.
 * {@code outputStrategy} selects how the model must shape its action.
 * {@code STRICT_TOOL} is allowed only when {@code allowBeta} is true.
 * {@code decisionTimeoutMs} is the decision deadline stored with this
 * snapshot.
 */
public record ModelConfiguration(
    String provider,
    String modelId,
    boolean thinking,
    double temperature,
    int maxOutputTokens,
    int decisionTimeoutMs,
    String credentialRef,
    String outputStrategy,
    boolean allowBeta) {

  public ModelConfiguration {
    requireText(provider, "provider");
    requireText(modelId, "modelId");
    requireText(outputStrategy, "outputStrategy");
    if (credentialRef != null && credentialRef.isBlank()) {
      throw new IllegalArgumentException("credentialRef");
    }
    if (maxOutputTokens < 1 || decisionTimeoutMs < 1
        || Double.isNaN(temperature) || temperature < 0) {
      throw new IllegalArgumentException("model");
    }
    if ("STRICT_TOOL".equals(outputStrategy) && !allowBeta) {
      throw new IllegalArgumentException("allowBeta");
    }
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name);
    }
  }
}
