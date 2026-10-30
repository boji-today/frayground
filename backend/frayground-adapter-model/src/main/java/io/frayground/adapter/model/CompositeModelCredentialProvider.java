package io.frayground.adapter.model;

import io.frayground.agent.ModelCredentialProvider;
import io.frayground.domain.strategy.ModelConfiguration;
import java.util.List;
import java.util.Optional;

/**
 * Selects the first credential provider that supports a configuration.
 *
 * <p>An empty secret from that provider is final. Later providers are not
 * asked, so a missing system key cannot fall through to another account.
 */
public final class CompositeModelCredentialProvider implements ModelCredentialProvider {
  private final List<ModelCredentialProvider> providers;

  public CompositeModelCredentialProvider(List<ModelCredentialProvider> providers) {
    this.providers = List.copyOf(providers);
  }

  @Override
  public boolean supports(ModelConfiguration configuration) {
    return providers.stream().anyMatch(provider -> provider.supports(configuration));
  }

  @Override
  public Optional<String> resolve(ModelConfiguration configuration) {
    for (ModelCredentialProvider provider : providers) {
      if (provider.supports(configuration)) {
        return provider.resolve(configuration);
      }
    }
    return Optional.empty();
  }
}
