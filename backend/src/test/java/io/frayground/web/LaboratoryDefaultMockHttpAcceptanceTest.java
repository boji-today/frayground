package io.frayground.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

import io.frayground.config.LaboratoryProperties;
import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

/** Verifies the production MockAgentRuntime itself completes a legal game over HTTP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("laboratory")
@Import(LaboratoryDefaultMockHttpAcceptanceTest.DatabaseStub.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LaboratoryDefaultMockHttpAcceptanceTest {
  @TestConfiguration(proxyBeanMethods = false)
  static class DatabaseStub { @Bean DataSource dataSource() { return mock(DataSource.class); } }

  @LocalServerPort int port;
  @Autowired LaboratoryProperties properties;

  @Test void productionMockRuntimeCompletesGameAndExposesEveryDecision() throws Exception {
    JsonNode room = request("POST", "/api/v1/rooms/current", "", true);
    UUID matchId = UUID.fromString(room.path("currentMatch").path("matchId").asText());
    assertEquals("READY", room.path("currentMatch").path("status").asText());
    assertEquals(0, room.path("currentMatch").path("stateVersion").asInt());
    assertEquals(0, room.path("currentMatch").path("game").path("moveCount").asInt());
    assertEquals("STEP", room.path("executionMode").asText());
    assertEquals("PAUSED", room.path("executionStatus").asText());
    assertEquals(matchId.toString(), request("POST", "/api/v1/rooms/current", "", true)
        .path("currentMatch").path("matchId").asText());
    assertEquals(6, room.path("currentMatch").path("game").path("board").size());
    for (JsonNode row : room.path("currentMatch").path("game").path("board")) {
      assertEquals(7, row.size());
      for (JsonNode cell : row) assertEquals("EMPTY", cell.asText());
    }
    var api = new LaboratoryGameHttpClient(port, properties.roomId());
    String stepCommand = api.commandBody("STEP");
    api.sendCommand(stepCommand);
    await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
      JsonNode stepped = request("GET", "/api/v1/rooms/current", "", false);
      assertEquals("PAUSED", stepped.path("executionStatus").asText());
      assertEquals(1, stepped.path("currentMatch").path("game").path("moveCount").asInt());
      assertEquals(1, stepped.path("currentMatch").path("stateVersion").asInt());
      int discs = 0;
      for (JsonNode row : stepped.path("currentMatch").path("game").path("board"))
        for (JsonNode cell : row) if (!"EMPTY".equals(cell.asText())) discs++;
      assertEquals(1, discs);
      assertEquals(1, api.decisions(matchId, 2).size());
    });
    command("CONTINUE");
    await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
        assertEquals("FINISHED", request("GET", "/api/v1/rooms/current", "", false)
            .path("currentMatch").path("status").asText()));

    var history = LaboratoryGameAssertions.finishedHistory(api, matchId);
    assertTrue(history.match().path("result").path("status").asText().matches("WIN|DRAW"));
    await().during(Duration.ofMillis(600)).atMost(Duration.ofSeconds(3))
        .pollInterval(Duration.ofMillis(150)).untilAsserted(() -> {
          assertEquals(history.decisions().size(), request("GET", "/api/v1/matches/" + matchId
              + "/decisions?limit=100", "", false).path("items").size());
          assertEquals(history.match(), request("GET", "/api/v1/matches/" + matchId, "", false));
        });
    LaboratoryGameAssertions.resetPreservesHistory(api, room, history, stepCommand);
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
