package io.frayground.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.awaitility.Awaitility.await;

import io.frayground.adapter.agent.FixedLaboratoryBindings;
import io.frayground.agent.RuntimeExecutionResult;
import io.frayground.agent.RuntimeInput;
import io.frayground.agent.RuntimeOutcome;
import io.frayground.agent.runtime.MockAgentRuntime;
import io.frayground.application.query.LaboratoryQueryService;
import io.frayground.config.LaboratoryProperties;
import io.frayground.domain.match.ExecutionStatus;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("laboratory")
@Import(LaboratoryPromptHttpTest.DatabaseStub.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LaboratoryPromptHttpTest {
  @TestConfiguration(proxyBeanMethods = false)
  static class DatabaseStub { @Bean DataSource dataSource() { return mock(DataSource.class); } }

  @Autowired WebApplicationContext context;
  @Autowired LaboratoryRequestFilter filter;
  @Autowired LaboratoryProperties properties;
  @Autowired LaboratoryQueryService queries;
  @Autowired FixedLaboratoryBindings bindings;
  @MockitoBean MockAgentRuntime mockRuntime;
  final JsonMapper json = JsonMapper.builder().build();
  MockMvc mvc;

  @BeforeEach void setup() { mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filter).build(); }

  @Test void draftPublishFeedsTheNextDecisionAndLeavesTheRunningOneBound() throws Exception {
    String missing = "99999999-0000-4000-8000-000000000009";
    mvc.perform(get("/api/v1/agents/" + missing + "/configuration")).andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("AGENT_NOT_FOUND"));
    mvc.perform(put("/api/v1/agents/" + properties.agentA() + "/prompt-draft").contentType("application/json")
        .content("{\"expectedDraftRevision\":1,\"content\":\"x\"}")).andExpect(status().isForbidden());

    mvc.perform(post("/api/v1/rooms/current").header("X-Frayground-Client", "laboratory")).andExpect(status().isCreated());
    UUID agent = properties.agentA();
    var original = bindings.current(agent).orElseThrow();
    String config = mvc.perform(get("/api/v1/agents/" + agent + "/configuration")).andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Agent A"))
        .andExpect(jsonPath("$.draft.revision").value(1))
        .andExpect(jsonPath("$.draft.content").value(original.prompt().content()))
        .andExpect(jsonPath("$.publishedPrompt.promptVersionId").value(original.prompt().promptVersionId().toString()))
        .andExpect(jsonPath("$.configuration.runtimeType").value(original.configuration().runtimeType().name()))
        .andExpect(jsonPath("$.configuration.model.provider").value(original.configuration().model().provider()))
        .andExpect(jsonPath("$.configuration.memoryPolicy.mode").value("STATELESS"))
        .andReturn().getResponse().getContentAsString();
    assertFalse(config.contains("credentialRef"));

    var seen = new CopyOnWriteArrayList<UUID>();
    var calls = new AtomicInteger();
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(mockRuntime.decideWithEvidence(any(), any())).thenAnswer(call -> {
      RuntimeInput input = call.getArgument(0);
      seen.add(input.hostedBindings().orElseThrow().prompt().promptVersionId());
      if (calls.getAndIncrement() == 0) {
        started.countDown();
        if (!release.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("release");
      }
      return CompletableFuture.completedFuture(new RuntimeExecutionResult(new RuntimeOutcome.SelectedAction("c0"), null));
    });
    try {
      step();
      assertTrue(started.await(5, TimeUnit.SECONDS));
      await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
          assertEquals("RUNNING", queries.decisions(queries.room(properties.roomId()).room().activeMatchId(), null, 10)
              .items().get(0).decision().status().name()));
      String running = mvc.perform(get("/api/v1/matches/" + queries.room(properties.roomId()).room().activeMatchId() + "/decisions"))
          .andExpect(jsonPath("$.items[0].status").value("RUNNING"))
          .andReturn().getResponse().getContentAsString();
      assertEquals(original.prompt().promptVersionId().toString(), json.readTree(running).path("items").get(0).path("promptVersionId").asText());

      mvc.perform(put(draftPath()).header("X-Frayground-Client", "laboratory").contentType("application/json")
          .content("{\"expectedDraftRevision\":1,\"content\":\"Prefer the center.\"}")).andExpect(status().isOk())
          .andExpect(jsonPath("$.revision").value(2)).andExpect(jsonPath("$.content").value("Prefer the center."));
      mvc.perform(put(draftPath()).header("X-Frayground-Client", "laboratory").contentType("application/json")
          .content("{\"expectedDraftRevision\":1,\"content\":\"stale\"}")).andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("DRAFT_REVISION_CONFLICT"));
      mvc.perform(post(publishPath()).header("X-Frayground-Client", "laboratory").contentType("application/json")
          .content("{\"commandId\":\"10000000-0000-4000-8000-000000000031\",\"expectedRoomRevision\":1,\"expectedMatchId\":\""
              + queries.room(properties.roomId()).room().activeMatchId() + "\",\"expectedDraftRevision\":2,\"content\":\"no\"}"))
          .andExpect(status().isBadRequest());

      var room = queries.room(properties.roomId()).room();
      String publish = publishBody("10000000-0000-4000-8000-000000000041", room.revision(), room.activeMatchId(), 2);
      String first = mvc.perform(post(publishPath()).header("X-Frayground-Client", "laboratory").contentType("application/json").content(publish))
          .andExpect(status().isCreated()).andExpect(jsonPath("$.versionNo").value(2))
          .andExpect(jsonPath("$.sourceDraftRevision").value(2))
          .andExpect(jsonPath("$.content").value("Prefer the center."))
          .andReturn().getResponse().getContentAsString();
      String replay = mvc.perform(post(publishPath()).header("X-Frayground-Client", "laboratory").contentType("application/json").content(publish))
          .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
      assertEquals(json.readTree(first), json.readTree(replay));
      UUID version2 = UUID.fromString(json.readTree(first).path("promptVersionId").asText());
      long afterReplay = queries.room(properties.roomId()).room().revision();
      String second = publishBody("10000000-0000-4000-8000-000000000042", afterReplay, room.activeMatchId(), 2);
      String third = mvc.perform(post(publishPath()).header("X-Frayground-Client", "laboratory").contentType("application/json").content(second))
          .andExpect(status().isCreated()).andExpect(jsonPath("$.versionNo").value(3))
          .andReturn().getResponse().getContentAsString();
      UUID version3 = UUID.fromString(json.readTree(third).path("promptVersionId").asText());
      mvc.perform(post(publishPath()).header("X-Frayground-Client", "laboratory").contentType("application/json")
          .content(publishBody("10000000-0000-4000-8000-000000000041", afterReplay, room.activeMatchId(), 2)))
          .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COMMAND_ID_CONFLICT"));
      assertEquals(original.prompt().content(), bindings.versions(agent, original.prompt().promptVersionId(), original.configuration().configVersionId()).orElseThrow().prompt().content());
      assertEquals(version2, bindings.versions(agent, version2, original.configuration().configVersionId()).orElseThrow().prompt().promptVersionId());
      assertEquals(version3, bindings.current(agent).orElseThrow().prompt().promptVersionId());
      assertEquals(original.prompt().promptVersionId().toString(), json.readTree(mvc.perform(get("/api/v1/matches/"
          + room.activeMatchId() + "/decisions")).andReturn().getResponse().getContentAsString()).path("items").get(0).path("promptVersionId").asText());
      assertTrue(queries.roomEvents(properties.roomId(), 0, 100).stream().anyMatch(event ->
          "PROMPT_PUBLISHED".equals(event.type()) && version3.toString().equals(String.valueOf(event.payload().get("promptVersionId")))
              && ((Number) event.payload().get("versionNo")).longValue() == 3L));

      release.countDown();
      await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
        var current = queries.room(properties.roomId());
        assertEquals(ExecutionStatus.PAUSED, current.room().executionStatus());
        assertEquals(1, current.currentMatch().match().stateVersion());
      });
      String done = mvc.perform(get("/api/v1/decisions/" + json.readTree(running).path("items").get(0).path("decisionId").asText()))
          .andExpect(jsonPath("$.status").value("COMPLETED"))
          .andExpect(jsonPath("$.promptVersionId").value(original.prompt().promptVersionId().toString()))
          .andExpect(jsonPath("$.submittedAction.type").value("place_disc"))
          .andExpect(jsonPath("$.submittedAction.column").value(0))
          .andReturn().getResponse().getContentAsString();
      String seat = json.readTree(done).path("participantId").asText();
      mvc.perform(get("/api/v1/matches/" + room.activeMatchId() + "/participants/" + seat + "/observation"))
          .andExpect(status().isOk()).andExpect(jsonPath("$.observation.legalColumns").isArray());
      step();
      await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertEquals(2, queries.match(room.activeMatchId()).match().stateVersion()));
      step();
      await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertEquals(3, seen.size()));
      assertEquals(original.prompt().promptVersionId(), seen.get(0));
      assertEquals(version3, seen.get(2));
      assertEquals(agent, bindings.current(agent).orElseThrow().prompt().agentId());
    } finally { release.countDown(); }
  }

  private void step() throws Exception {
    var room = queries.room(properties.roomId()).room();
    String body = "{\"commandId\":\"" + UUID.randomUUID() + "\",\"expectedRoomRevision\":" + room.revision()
        + ",\"expectedMatchId\":\"" + room.activeMatchId() + "\",\"type\":\"step\"}";
    mvc.perform(post("/api/v1/rooms/" + room.roomId() + "/commands").header("X-Frayground-Client", "laboratory")
        .contentType("application/json").content(body)).andExpect(status().isOk());
  }

  private String draftPath() { return "/api/v1/agents/" + properties.agentA() + "/prompt-draft"; }
  private String publishPath() { return "/api/v1/rooms/" + properties.roomId() + "/agents/" + properties.agentA() + "/prompt-versions"; }
  private static String publishBody(String commandId, long revision, UUID matchId, long draftRevision) {
    return "{\"commandId\":\"" + commandId + "\",\"expectedRoomRevision\":" + revision
        + ",\"expectedMatchId\":\"" + matchId + "\",\"expectedDraftRevision\":" + draftRevision + "}";
  }
}
