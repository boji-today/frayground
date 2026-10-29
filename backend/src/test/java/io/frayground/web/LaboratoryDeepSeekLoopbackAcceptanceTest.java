package io.frayground.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import io.frayground.config.LaboratoryProperties;
import io.frayground.application.port.DecisionRecordStore;
import io.frayground.agent.AgentRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
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

/** Full game through the real DeepSeek adapter, with its HTTP endpoint redirected to loopback. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("laboratory")
@Import(LaboratoryDeepSeekLoopbackAcceptanceTest.DatabaseStub.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class LaboratoryDeepSeekLoopbackAcceptanceTest {
  private static final LocalDeepSeekServer model = new LocalDeepSeekServer();
  private static final AtomicReference<UUID> observedDecision = new AtomicReference<>();
  private static final AtomicReference<CountDownLatch> runnerCompleted = new AtomicReference<>();

  @TestConfiguration(proxyBeanMethods = false)
  static class DatabaseStub {
    @Bean DataSource dataSource() { return mock(DataSource.class); }
    @Bean @Primary AgentRunner runnerCompletionObserver(@Qualifier("decisionAgentRunner") AgentRunner delegate) {
      return (input, cancellation) -> delegate.run(input, cancellation).whenComplete((result, error) -> {
        if (input.publicRequest().decisionId().equals(observedDecision.get())) {
          CountDownLatch latch = runnerCompleted.get(); if (latch != null) latch.countDown();
        }
      });
    }
  }

  @LocalServerPort int port;
  @Autowired LaboratoryProperties properties;
  @Autowired DataSource dataSource;
  @Autowired DecisionRecordStore records;
  @Autowired io.frayground.adapter.agent.FixedLaboratoryBindings bindings;

  @BeforeEach void resetFakeServer() {
    model.reset();
    observedDecision.set(null); runnerCompleted.set(null);
  }

  @DynamicPropertySource
  static void modelConfiguration(DynamicPropertyRegistry registry) {
    registry.add("frayground.laboratory.agents.a.runtime", () -> "LLM");
    registry.add("frayground.laboratory.agents.b.runtime", () -> "LLM");
    registry.add("frayground.laboratory.agents.a.prompt", () -> "Agent A private prompt");
    registry.add("frayground.laboratory.agents.b.prompt", () -> "Agent B private prompt");
    registry.add("frayground.model.vendors.deepseek.base-url",
        () -> "http://127.0.0.1:" + model.port());
    registry.add("frayground.model.system-api-keys.deepseek-flash", () -> "loopback-test-key");
  }

  @AfterAll static void stopModelServer() { model.close(); }

  @Test
  void twoConfiguredLlmAgentsFinishGameThroughTheDeepSeekHttpAdapter() throws Exception {
    JsonNode created = request("POST", "/api/v1/rooms/current", "", true);
    UUID match = UUID.fromString(created.path("currentMatch").path("matchId").asText());
    command("START");
    await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
        assertEquals("FINISHED", request("GET", "/api/v1/rooms/current", "", false)
            .path("currentMatch").path("status").asText()));
    JsonNode finished = request("GET", "/api/v1/matches/" + match, "", false);
    int moves = finished.path("game").path("moveCount").asInt();
    assertEquals(7, moves, "the fake model should follow the known A win sequence");
    assertEquals(moves, model.requests.get(), "one model request per move, without retries");
    assertEquals(moves, model.traces.size());
    for (int i = 0; i < model.traces.size(); i++) {
      LocalDeepSeekServer.RequestTrace trace = model.traces.get(i);
      assertEquals(i % 2 == 0 ? "Agent A private prompt" : "Agent B private prompt", trace.prompt());
      assertTrue(trace.candidates().contains(trace.selectedActionId()), "selected candidate must be offered");
    }
    var history = LaboratoryGameAssertions.finishedHistory(
        new LaboratoryGameHttpClient(port, properties.roomId()), match);
    var expectedByParticipant = new java.util.HashMap<String, io.frayground.agent.HostedBindings>();
    for (JsonNode p : created.path("currentMatch").path("participants")) {
      UUID agentId = UUID.fromString(p.path("agentId").asText());
      expectedByParticipant.put(p.path("participantId").asText(), bindings.current(agentId).orElseThrow());
    }
    for (int i = 0; i < history.decisions().size(); i++) {
      JsonNode decision = history.decisions().get(i);
      var expected = expectedByParticipant.get(decision.path("participantId").asText());
      assertNotNull(expected);
      assertEquals(expected.prompt().promptVersionId().toString(), decision.path("promptVersionId").asText());
      assertEquals(expected.configuration().configVersionId().toString(), decision.path("configVersionId").asText());
      assertEquals(expected.prompt().content(), model.traces.get(i).prompt());
      assertEquals(10, decision.path("usage").path("inputTokens").asInt());
      assertEquals(2, decision.path("usage").path("outputTokens").asInt());
      assertEquals("c" + history.details().get(i).path("submittedAction").path("column").asInt(),
          model.traces.get(i).selectedActionId());
    }
    verifyNoInteractions(dataSource);
  }

  @Test
  void pauseErrorsContinueAndResetIgnoreLateHttpResponse() throws Exception {
    JsonNode created = request("POST", "/api/v1/rooms/current", "", true);
    UUID oldMatch = UUID.fromString(created.path("currentMatch").path("matchId").asText());

    var received = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    model.responseReceived.set(received); model.releaseResponse.set(release); model.blockNext.set(true);
    command("START");
    assertTrue(received.await(5, TimeUnit.SECONDS), "model call should reach fake server");
    command("PAUSE");
    release.countDown();
    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
      JsonNode room = request("GET", "/api/v1/rooms/current", "", false);
      assertEquals("PAUSED", room.path("executionStatus").asText());
      assertEquals(1, room.path("currentMatch").path("game").path("moveCount").asInt());
    });

    JsonNode boardBeforeErrors = request("GET", "/api/v1/rooms/current", "", false).path("currentMatch").path("game").path("board");
    var beforeErrors = new LaboratoryGameHttpClient(port, properties.roomId()).decisions(oldMatch, 2);
    assertEquals(1, beforeErrors.size());
    model.nextStatus.set(500);
    command("CONTINUE");
    awaitPausedAtMoveCount(1);
    String httpFailure = assertFailure(oldMatch, "MODEL_UNAVAILABLE", boardBeforeErrors);
    assertNotEquals(beforeErrors.get(0).path("decisionId").asText(), httpFailure);
    model.nextContent.set("{\"selectedActionId\":\"c99\"}");
    command("CONTINUE");
    awaitPausedAtMoveCount(1);
    String invalidFailure = assertFailure(oldMatch, "MODEL_OUTPUT_INVALID", boardBeforeErrors);
    assertNotEquals(httpFailure, invalidFailure);
    command("CONTINUE");
    await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
        assertEquals("FINISHED", request("GET", "/api/v1/rooms/current", "", false)
            .path("currentMatch").path("status").asText()));

    JsonNode old = request("GET", "/api/v1/matches/" + oldMatch, "", false);
    assertEquals(7, old.path("game").path("moveCount").asInt());
    var recovered = new LaboratoryGameHttpClient(port, properties.roomId()).decisions(oldMatch, 2);
    assertEquals(9, recovered.size(), "seven successful and two failed decisions");
    var recoveredIds = new java.util.HashSet<String>();
    int successes = 0;
    for (JsonNode d : recovered) {
      assertTrue(recoveredIds.add(d.path("decisionId").asText()));
      if ("COMPLETED".equals(d.path("status").asText())) {
        successes++;
        assertNotEquals(httpFailure, d.path("decisionId").asText());
        assertNotEquals(invalidFailure, d.path("decisionId").asText());
      }
    }
    assertEquals(7, successes);
    assertEquals(9, model.requests.get(), "manual continue creates a fresh request for each failed decision");

    command("RESET");
    JsonNode fresh = request("GET", "/api/v1/rooms/current", "", false);
    UUID freshMatch = UUID.fromString(fresh.path("currentMatch").path("matchId").asText());

    var lateReceived = new CountDownLatch(1);
    var lateRelease = new CountDownLatch(1);
    model.responseReceived.set(lateReceived); model.releaseResponse.set(lateRelease); model.blockNext.set(true);
    command("START");
    assertTrue(lateReceived.await(5, TimeUnit.SECONDS), "next model call should reach fake server");
    JsonNode pendingDecisions = request("GET", "/api/v1/matches/" + freshMatch + "/decisions?limit=100", "", false);
    UUID lateDecisionId = UUID.fromString(pendingDecisions.path("items").get(0).path("decisionId").asText());
    observedDecision.set(lateDecisionId);
    var completed = new CountDownLatch(1); runnerCompleted.set(completed);
    JsonNode resetAck = command("RESET");
    String afterLateResetMatch = resetAck.path("matchId").asText();
    var sent = new CountDownLatch(1); model.responseSent.set(sent);
    lateRelease.countDown();
    assertTrue(sent.await(5, TimeUnit.SECONDS), "fake HTTP response should be delivered");
    await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
        assertNotNull(records.find(lateDecisionId).orElseThrow().modelOutputJson(),
            "late model output must be audited before checking reset state"));
    assertTrue(completed.await(10, TimeUnit.SECONDS), "agent runner must finish late-response handling");
    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
      JsonNode afterReset = request("GET", "/api/v1/rooms/current", "", false);
      assertEquals(afterLateResetMatch, afterReset.path("currentMatch").path("matchId").asText());
      assertEquals("READY", afterReset.path("currentMatch").path("status").asText());
      assertEquals(0, afterReset.path("currentMatch").path("game").path("moveCount").asInt());
    });
    assertEquals("ABORTED", request("GET", "/api/v1/matches/" + freshMatch, "", false)
        .path("status").asText());
    verifyNoInteractions(dataSource);
  }

  private String assertFailure(UUID match, String code, JsonNode board) throws Exception {
    assertEquals(board, request("GET", "/api/v1/rooms/current", "", false).path("currentMatch").path("game").path("board"));
    JsonNode decisions = request("GET", "/api/v1/matches/" + match + "/decisions?limit=100", "", false);
    int count = 0;
    String id = null;
    for (JsonNode decision : decisions.path("items")) {
      if (code.equals(decision.path("errorCode").asText())) {
        assertEquals("FAILED", decision.path("status").asText());
        assertEquals(1, decision.path("stateVersion").asInt());
        id = decision.path("decisionId").asText();
        count++;
      }
    }
    assertEquals(1, count);
    return id;
  }

  private void awaitPausedAtMoveCount(int expected) {
    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
      JsonNode room = request("GET", "/api/v1/rooms/current", "", false);
      assertEquals("PAUSED", room.path("executionStatus").asText());
      assertEquals(expected, room.path("currentMatch").path("game").path("moveCount").asInt());
    });
  }

  private JsonNode command(String type) throws Exception {
    var api = new LaboratoryGameHttpClient(port, properties.roomId());
    return api.sendCommand(api.commandBody(type));
  }

  private JsonNode request(String method, String path, String body, boolean mutation) throws Exception {
    return new LaboratoryGameHttpClient(port, properties.roomId()).request(method, path, body, mutation);
  }


}
