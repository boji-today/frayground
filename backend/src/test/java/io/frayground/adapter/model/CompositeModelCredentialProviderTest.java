package io.frayground.adapter.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.frayground.agent.ModelCredentialProvider;
import io.frayground.domain.strategy.ModelConfiguration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CompositeModelCredentialProviderTest {

  @Test
  void dispatchesBySchemeAndStopsAtTheFirstSupport() {
    Fake system = new Fake("system:");
    Fake user = new Fake("user:");
    CompositeModelCredentialProvider provider = new CompositeModelCredentialProvider(
        java.util.List.of(system, user));

    assertThat(provider.resolve(configuration("system:deepseek-flash"))).contains("system:key");
    assertThat(system.resolveCalls).isEqualTo(1);
    assertThat(user.resolveCalls).isZero();

    assertThat(provider.resolve(configuration("user:account"))).contains("user:key");
    assertThat(user.resolveCalls).isEqualTo(1);
    assertThat(system.resolveCalls).isEqualTo(1);
  }

  @Test
  void userSchemeIsUnsupportedWithoutAUserProvider() {
    CompositeModelCredentialProvider provider = new CompositeModelCredentialProvider(
        java.util.List.of(new Fake("system:")));

    assertThat(provider.supports(configuration("user:account"))).isFalse();
  }

  @Test
  void emptySecretDoesNotFallThrough() {
    Fake system = new Fake("system:");
    system.value = Optional.empty();
    Fake user = new Fake("user:");
    user.supportsPrefix = "system:";
    CompositeModelCredentialProvider provider = new CompositeModelCredentialProvider(
        java.util.List.of(system, user));

    assertThat(provider.resolve(configuration("system:deepseek-flash"))).isEmpty();
    assertThat(user.resolveCalls).isZero();
  }

  @Test
  void doesNotResolveAnUnsupportedProvider() {
    Fake system = new Fake("system:");
    CompositeModelCredentialProvider provider = new CompositeModelCredentialProvider(
        java.util.List.of(system));

    assertThat(provider.resolve(configuration("user:account"))).isEmpty();
    assertThat(system.resolveCalls).isZero();
  }

  private static ModelConfiguration configuration(String credentialRef) {
    return new ModelConfiguration(
        "deepseek", "deepseek-flash", false, 0, 16, 1_000, credentialRef, "JSON_MODE", false);
  }

  private static final class Fake implements ModelCredentialProvider {
    private int resolveCalls;
    private String supportsPrefix;
    private Optional<String> value;

    private Fake(String supportsPrefix) {
      this.supportsPrefix = supportsPrefix;
      this.value = Optional.of(supportsPrefix + "key");
    }

    @Override
    public boolean supports(ModelConfiguration configuration) {
      String reference = configuration.credentialRef();
      return reference != null && reference.startsWith(supportsPrefix);
    }

    @Override
    public Optional<String> resolve(ModelConfiguration configuration) {
      resolveCalls++;
      return value;
    }
  }
}
