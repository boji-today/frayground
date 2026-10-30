package io.frayground.web;

import static org.junit.jupiter.api.Assertions.*;

import io.frayground.config.LaboratoryProperties;
import java.time.Duration;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.mockito.Mockito.mock;

/** Deliberately opt-in: never run against the live service in the default test suite. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("laboratory")
@Import(DeepSeekLiveGameSmokeTest.DatabaseStub.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DeepSeekLiveGameSmokeTest {
  @TestConfiguration(proxyBeanMethods = false)
  static class DatabaseStub { @Bean DataSource dataSource() { return mock(DataSource.class); } }

  @DynamicPropertySource
  static void configureLiveAgents(DynamicPropertyRegistry registry) {
    registry.add("frayground.laboratory.agents.a.runtime", () -> "LLM");
    registry.add("frayground.laboratory.agents.b.runtime", () -> "LLM");
  }

  @LocalServerPort int port;
  @Autowired LaboratoryProperties properties;
  @Test
  @EnabledIfEnvironmentVariable(named = "FRAYGROUND_RUN_DEEPSEEK_SMOKE", matches = "true")
  void optInLiveGameUsesAtMostFortyTwoCallsAndReportsOnlySafeSummary() throws Exception {
    assertNotNull(key(),
        "live smoke opt-in requires DEEPSEEK_API_KEY or FRAYGROUND_MODEL_SYSTEM_API_KEYS_DEEPSEEK_FLASH");
    assertFalse(key().isBlank(),
        "live smoke key must not be blank");
    // The bounded game interval includes initialization, control and final queries.
    var api = new LaboratoryGameHttpClient(port, properties.roomId(),
        Instant.now().plus(Duration.ofMinutes(23)), Duration.ofSeconds(5));
    LaboratoryGameSmokeRunner.run(api, Duration.ofSeconds(5), System.out);
  }

  private static String key() {
    String legacy = System.getenv("FRAYGROUND_MODEL_SYSTEM_API_KEYS_DEEPSEEK_FLASH");
    return legacy != null ? legacy : System.getenv("DEEPSEEK_API_KEY");
  }

}
