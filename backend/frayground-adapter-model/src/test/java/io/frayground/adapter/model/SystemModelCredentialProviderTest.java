package io.frayground.adapter.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.frayground.domain.strategy.ModelConfiguration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SystemModelCredentialProviderTest {
  private final SystemModelCredentialProvider provider = new SystemModelCredentialProvider(
      Map.of("deepseek-flash", "flash-key", "other-model", "other-key"));

  @Test
  void returnsTheKeyForTheRequestedModel() {
    ModelConfiguration configuration = configuration("deepseek-flash", "system:deepseek-flash");

    assertThat(provider.supports(configuration)).isTrue();
    assertThat(provider.resolve(configuration)).contains("flash-key");
  }

  @Test
  void rejectsANullReference() {
    assertThat(provider.supports(configuration("deepseek-flash", null))).isFalse();
  }

  @Test
  void rejectsAReferenceForAnotherModel() {
    assertThat(provider.supports(configuration("other-model", "system:deepseek-flash"))).isFalse();
  }

  @Test
  void missingOrBlankKeyResolvesEmpty() {
    SystemModelCredentialProvider empty = new SystemModelCredentialProvider(Map.of());
    SystemModelCredentialProvider blank = new SystemModelCredentialProvider(
        Map.of("deepseek-flash", " "));
    ModelConfiguration configuration = configuration("deepseek-flash", "system:deepseek-flash");

    assertThat(empty.supports(configuration)).isTrue();
    assertThat(empty.resolve(configuration)).isEmpty();
    assertThat(blank.supports(configuration)).isTrue();
    assertThat(blank.resolve(configuration)).isEmpty();
  }

  @Test
  void doesNotSupportAUserReference() {
    assertThat(provider.supports(configuration("deepseek-flash", "user:account"))).isFalse();
  }

  @Test
  void resolveReturnsEmptyForUnsupportedReferences() {
    assertThat(provider.resolve(configuration("deepseek-flash", "user:account"))).isEmpty();
    assertThat(provider.resolve(configuration("deepseek-flash", "system:other-model"))).isEmpty();
    assertThat(provider.resolve(configuration("deepseek-flash", null))).isEmpty();
  }

  private static ModelConfiguration configuration(String modelId, String credentialRef) {
    return new ModelConfiguration(
        "deepseek", modelId, false, 0, 16, 1_000, credentialRef, "JSON_MODE", false);
  }
}
