package io.frayground.web;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.frayground.config.LaboratoryProperties;
import java.time.Duration;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
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
import tools.jackson.databind.JsonNode;

/** Proves mixed seat configuration reaches the actual mock and DeepSeek runtimes in one HTTP game. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("laboratory")
@Import(LaboratoryMixedRuntimeHttpAcceptanceTest.DatabaseStub.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LaboratoryMixedRuntimeHttpAcceptanceTest {
  private static final LocalDeepSeekServer model = new LocalDeepSeekServer(false);

  @TestConfiguration(proxyBeanMethods = false)
  static class DatabaseStub { @Bean DataSource dataSource() { return mock(DataSource.class); } }

  @DynamicPropertySource static void configure(DynamicPropertyRegistry registry) {
    registry.add("frayground.laboratory.agents.a.runtime", () -> "MOCK");
    registry.add("frayground.laboratory.agents.a.prompt", () -> "Mock only prompt");
    registry.add("frayground.laboratory.agents.b.runtime", () -> "LLM");
    registry.add("frayground.laboratory.agents.b.prompt", () -> "LLM only prompt");
    registry.add("frayground.model.vendors.deepseek.base-url", () -> "http://127.0.0.1:" + model.port());
    registry.add("frayground.model.system-api-keys.deepseek-flash", () -> "loopback-test-key");
  }

  @AfterAll static void stopServer() { model.close(); }

  @LocalServerPort int port;
  @Autowired LaboratoryProperties properties;

  @Test void mixedMockAndLlmSeatsCompleteGameAndOnlyLlmSeatCallsModel() throws Exception {
    model.reset();
    JsonNode room = request("POST", "/api/v1/rooms/current", "", true);
    UUID matchId = UUID.fromString(room.path("currentMatch").path("matchId").asText());
    command("START");
    await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
        assertEquals("FINISHED", request("GET", "/api/v1/rooms/current", "", false)
            .path("currentMatch").path("status").asText()));
    JsonNode match = request("GET", "/api/v1/matches/" + matchId, "", false);
    int moves = match.path("game").path("moveCount").asInt();
    assertTrue(moves > 0 && moves <= 42);
    assertEquals(moves / 2, model.requests.get(), "only B/LLM turns should call the local model");
    assertEquals(model.requests.get(), model.traces.size());
    assertTrue(model.traces.stream().allMatch(t -> "LLM only prompt".equals(t.prompt())));
    var history = LaboratoryGameAssertions.finishedHistory(
        new LaboratoryGameHttpClient(port, properties.roomId()), matchId);
    int llmDecisions = 0;
    for (JsonNode decision : history.decisions()) {
      boolean llm = false;
      for (JsonNode participant : room.path("currentMatch").path("participants")) {
        if (participant.path("participantId").equals(decision.path("participantId")))
          llm = properties.agentB().toString().equals(participant.path("agentId").asText());
      }
      if (llm) {
        var trace = model.traces.get(llmDecisions++);
        assertTrue(trace.candidates().contains(trace.selectedActionId()));
        JsonNode detail = history.details().stream().filter(d -> d.path("decisionId").equals(decision.path("decisionId")))
            .findFirst().orElseThrow();
        assertEquals("c" + detail.path("submittedAction").path("column").asInt(), trace.selectedActionId());
        assertEquals(10, decision.path("usage").path("inputTokens").asInt());
        assertEquals(2, decision.path("usage").path("outputTokens").asInt());
      } else {
        assertTrue(decision.path("usage").isNull(), "Mock decisions must not acquire model usage");
      }
    }
    assertEquals(model.requests.get(), llmDecisions);
    verifyNoInteractions(dataSource);
  }

  @Autowired DataSource dataSource;

  private void command(String type) throws Exception {
    var api = new LaboratoryGameHttpClient(port, properties.roomId());
    api.sendCommand(api.commandBody(type));
  }

  private JsonNode request(String method, String path, String body, boolean mutation) throws Exception {
    return new LaboratoryGameHttpClient(port, properties.roomId()).request(method, path, body, mutation);
  }
}
