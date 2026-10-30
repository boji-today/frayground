package io.frayground.adapter.model;

import io.frayground.agent.ModelCredentialProvider;
import io.frayground.domain.strategy.ModelConfiguration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Platform keys configured for each model id.
 *
 * <p>Supports only {@code system:<modelId>} when that id is the model being
 * called. A blank configured value is supported and resolves empty.
 */
public final class SystemModelCredentialProvider implements ModelCredentialProvider {
  private static final String SCHEME = "system:";

  private final Map<String, String> apiKeys;

  public SystemModelCredentialProvider(Map<String, String> apiKeys) {
    Map<String, String> copied = new LinkedHashMap<>();
    apiKeys.forEach((modelId, apiKey) -> {
      if (modelId != null && apiKey != null) {
        copied.put(modelId, apiKey);
      }
    });
    this.apiKeys = Map.copyOf(copied);
  }

  @Override
  public boolean supports(ModelConfiguration configuration) {
    String reference = configuration.credentialRef();
    if (reference == null || !reference.startsWith(SCHEME)) {
      return false;
    }
    String modelId = reference.substring(SCHEME.length());
    return !modelId.isBlank() && modelId.equals(configuration.modelId());
  }

  @Override
  public Optional<String> resolve(ModelConfiguration configuration) {
    if (!supports(configuration)) {
      return Optional.empty();
    }
    String apiKey = apiKeys.get(configuration.modelId());
    if (apiKey == null || apiKey.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(apiKey);
  }
}
