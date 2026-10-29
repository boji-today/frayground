package io.frayground.adapter.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ModelConfigurationYamlTest {

  @Test
  void committedConfigHasAnEmptyDefaultAndAConnectionLimit() throws IOException {
    String yaml = new String(
        getClass().getClassLoader().getResourceAsStream("application.yml").readAllBytes(),
        StandardCharsets.UTF_8);

    assertThat(yaml).contains("max-connections: 8");
    assertThat(yaml).contains("deepseek-flash: ${DEEPSEEK_API_KEY:}");
    assertThat(yaml).doesNotContain("active: dev");
    assertThat(yaml).doesNotContain("sk-");
  }
  @Test
  void environmentAliasAndExistingOverrideResolveWithoutExposingKeys() throws Exception {
    var yaml = new org.springframework.boot.env.YamlPropertySourceLoader().load("config",
        new org.springframework.core.io.ClassPathResource("application.yml")).get(0);
    var environment = new org.springframework.core.env.StandardEnvironment();
    environment.getPropertySources().remove("systemEnvironment");
    environment.getPropertySources().remove("systemProperties");
    environment.getPropertySources().addLast(yaml);
    String property = "frayground.model.system-api-keys.deepseek-flash";
    assertThat(environment.getProperty(property)).isEmpty();
    environment.getPropertySources().addFirst(new org.springframework.core.env.SystemEnvironmentPropertySource(
        "testEnv", java.util.Map.of("DEEPSEEK_API_KEY", "alias-value")));
    assertThat(environment.getProperty(property)).isEqualTo("alias-value");
    environment.getPropertySources().replace("testEnv", new org.springframework.core.env.SystemEnvironmentPropertySource(
        "testEnv", java.util.Map.of("DEEPSEEK_API_KEY", "alias-value",
            "FRAYGROUND_MODEL_SYSTEM_API_KEYS_DEEPSEEK_FLASH", "existing-value")));
    assertThat(environment.getProperty(property)).isEqualTo("existing-value");
  }
}
