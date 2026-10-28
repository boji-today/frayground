package io.frayground.adapter.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ModelConfigurationYamlTest {

  @Test
  void committedConfigHasAnEmptySystemKeyAndAConnectionLimit() throws IOException {
    String yaml = new String(
        getClass().getClassLoader().getResourceAsStream("application.yml").readAllBytes(),
        StandardCharsets.UTF_8);

    assertThat(yaml).contains("max-connections: 8");
    assertThat(yaml).contains("deepseek-flash: \"\"");
    assertThat(yaml).doesNotContain("active: dev");
    assertThat(yaml).doesNotContain("sk-");
  }
}
