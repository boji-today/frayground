package io.frayground.adapter.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.frayground.agent.ModelAdapter;
import io.frayground.agent.ModelCredentialProvider;
import io.frayground.domain.strategy.ModelConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(ModelAdapterConfiguration.class)
@TestPropertySource(properties = {
    "frayground.model.vendors.deepseek.base-url=http://127.0.0.1:9",
    "frayground.model.vendors.deepseek.pool.max-connections=2",
    "frayground.model.system-api-keys.deepseek-flash=test-key"
})
class ModelAdapterConfigurationTest {
  @Autowired
  private ModelAdapter adapter;

  @Autowired
  private ModelCredentialProvider credentials;

  @Autowired
  private VendorHttpPools pools;

  @Test
  void registersDeepSeekAndTheSystemKey() {
    ModelConfiguration configuration = new ModelConfiguration(
        "deepseek", "deepseek-flash", false, 0, 16, 1_000, "system:deepseek-flash",
        "JSON_MODE", false);

    assertThat(adapter).isInstanceOf(DeepSeekModelAdapter.class);
    assertThat(credentials.resolve(configuration)).contains("test-key");
    assertThat(pools.get("deepseek").maxConnections()).isEqualTo(2);
  }
}
