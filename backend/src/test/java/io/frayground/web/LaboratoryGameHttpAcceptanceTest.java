package io.frayground.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import io.frayground.adapter.agent.FixedLaboratoryBindings;
import io.frayground.agent.input.DecisionInputReader;
import io.frayground.agent.RuntimeExecutionResult;
import io.frayground.agent.RuntimeOutcome;
import io.frayground.agent.runtime.MockAgentRuntime;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import static org.mockito.Mockito.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises HTTP controls with a deterministic seven-move runtime fixture. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("laboratory")
@Import(LaboratoryGameHttpAcceptanceTest.DatabaseStub.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LaboratoryGameHttpAcceptanceTest {
  @TestConfiguration(proxyBeanMethods = false)
  static class DatabaseStub {
    @Bean DataSource dataSource() { return mock(DataSource.class); }
  }

  @LocalServerPort int port;
  @Autowired LaboratoryProperties properties;
  @Autowired FixedLaboratoryBindings bindings;
  @Autowired DataSource dataSource;
  @MockitoBean MockAgentRuntime mockRuntime;
  final AtomicInteger decisionNo = new AtomicInteger();

  @org.junit.jupiter.api.BeforeEach
  void useWinningLegalSequence() {
    decisionNo.set(0);
    int[] columns = {0, 6, 1, 6, 2, 5, 3};
    when(mockRuntime.decideWithEvidence(any(), any())).thenAnswer(call -> {
      var input = call.getArgument(0, io.frayground.agent.RuntimeInput.class);
      int index = decisionNo.getAndIncrement();
      var spec = new DecisionInputReader().readSpecification(input.publicRequest());
      String selected = "c" + columns[Math.min(index, columns.length - 1)];
      boolean offered = false;
      for (var option : spec.options()) if (selected.equals(option.get("selectedActionId").asString())) offered = true;
      var outcome = offered ? new RuntimeOutcome.SelectedAction(selected)
          : new RuntimeOutcome.Failed(new io.frayground.agent.RuntimeError("TEST_SEQUENCE_INVALID", "TEST_SEQUENCE_INVALID"));
      return CompletableFuture.completedFuture(new RuntimeExecutionResult(outcome, null));
    });
  }

  @Test
  void mockAgentsCompleteGameAndResetKeepsOldMatchObservable() throws Exception {
    var a = bindings.current(properties.agentA()).orElseThrow();
    var b = bindings.current(properties.agentB()).orElseThrow();
    assertEquals("Choose a legal move.", a.prompt().content());
    assertEquals("Choose a legal move.", b.prompt().content());
    assertEquals("MOCK", a.configuration().runtimeType().name());
    assertEquals("MOCK", b.configuration().runtimeType().name());

    JsonNode created = request("POST", "/api/v1/rooms/current", "", true);
    assertEquals("PAUSED", created.path("executionStatus").asText());
    assertEquals("READY", created.path("currentMatch").path("status").asText());
    assertEquals(created.path("currentMatch").path("matchId"), request("POST", "/api/v1/rooms/current", "", true).path("currentMatch").path("matchId"));
    for (JsonNode row : created.path("currentMatch").path("game").path("board"))
      for (JsonNode cell : row) assertEquals("EMPTY", cell.asText());
    assertEquals(0, created.path("currentMatch").path("stateVersion").asInt());
    UUID match = UUID.fromString(created.path("currentMatch").path("matchId").asText());

    String oldCommand = command("STEP");
    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
      JsonNode state = request("GET", "/api/v1/rooms/current", "", false);
      assertEquals(1, state.path("currentMatch").path("game").path("moveCount").asInt());
      assertEquals("PAUSED", state.path("executionStatus").asText());
    });
    command("CONTINUE");
    await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
      JsonNode state = request("GET", "/api/v1/rooms/current", "", false);
      assertEquals("FINISHED", state.path("currentMatch").path("status").asText());
    });

    JsonNode finished = request("GET", "/api/v1/matches/" + match, "", false);
    int moves = finished.path("game").path("moveCount").asInt();
    assertEquals(7, moves, "the acceptance fixture must reach the known A win");
    assertEquals(finished.path("stateVersion").asInt(), moves);
    var api = new LaboratoryGameHttpClient(port, properties.roomId());
    var history = LaboratoryGameAssertions.finishedHistory(api, match);
    LaboratoryGameAssertions.resetPreservesHistory(api, created, history, oldCommand);
    verifyNoInteractions(dataSource);
  }

  private String command(String type) throws Exception {
    var api = new LaboratoryGameHttpClient(port, properties.roomId());
    String body = api.commandBody(type);
    JsonNode ack = api.sendCommand(body);
    if (type.equals("RESET")) assertEquals("PAUSED", ack.path("executionStatus").asText());
    return body;
  }

  private JsonNode request(String method, String path, String body, boolean mutation) throws Exception {
    return new LaboratoryGameHttpClient(port, properties.roomId()).request(method, path, body, mutation);
  }
}
