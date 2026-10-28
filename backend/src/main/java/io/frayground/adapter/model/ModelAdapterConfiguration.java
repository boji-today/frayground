package io.frayground.adapter.model;

import io.frayground.agent.ModelAdapter;
import io.frayground.agent.ModelCredentialProvider;
import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the DeepSeek adapter and the system credential provider.
 */
@Configuration
@EnableConfigurationProperties(ModelProperties.class)
public class ModelAdapterConfiguration {

  @Bean(destroyMethod = "close")
  VendorHttpPools vendorHttpPools(ModelProperties properties) {
    return new VendorHttpPools(properties);
  }

  @Bean
  ModelCredentialProvider modelCredentialProvider(ModelProperties properties) {
    return new CompositeModelCredentialProvider(
        List.of(new SystemModelCredentialProvider(properties.getSystemApiKeys())));
  }

  @Bean
  ModelAdapter deepSeekModelAdapter(
      ModelProperties properties,
      VendorHttpPools pools,
      ModelCredentialProvider credentials) {
    ModelProperties.Vendor vendor = properties.getVendors().get(DeepSeekModelAdapter.PROVIDER);
    if (vendor == null || vendor.getBaseUrl() == null || vendor.getBaseUrl().isBlank()) {
      throw new IllegalStateException("frayground.model.vendors.deepseek.base-url");
    }
    return new DeepSeekModelAdapter(
        pools.get(DeepSeekModelAdapter.PROVIDER),
        credentials,
        URI.create(vendor.getBaseUrl()));
  }
}
