package io.frayground.agent;

import io.frayground.domain.strategy.ModelConfiguration;
import java.util.Optional;

/**
 * Resolves the secret for one model call.
 *
 * <p>{@code system:<modelId>} is the platform key for that model.
 * {@code user:<credentialId>} is reserved for a future database-backed
 * provider and is not registered yet. A null reference is not a system key.
 * Implementations must not log the secret.
 */
public interface ModelCredentialProvider {
  boolean supports(ModelConfiguration configuration);

  /**
   * Returns the secret when {@link #supports} is true. Empty means the named
   * credential is absent. Callers must not invoke this when {@code supports}
   * is false.
   */
  Optional<String> resolve(ModelConfiguration configuration);
}
