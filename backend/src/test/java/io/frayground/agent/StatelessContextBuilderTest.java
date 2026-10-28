package io.frayground.agent;

import static org.junit.jupiter.api.Assertions.*;
import io.frayground.agent.context.StatelessContextBuilder;
import io.frayground.agent.input.DecisionInputReader;
import io.frayground.domain.strategy.*;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class StatelessContextBuilderTest {
  private final UUID agent = UUID.randomUUID();
  private DecisionRequest request(String mode, String observation) {
    return new DecisionRequest("1.0", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 1,
        "sample", "v1", "schema", "{\"type\":\"object\"}", "{\"mode\":\""+mode+"\",\"actionSchemaId\":\"schema\",\"options\":[{\"selectedActionId\":\"x\",\"action\":{}}],\"constraints\":{}}",
        "rules", observation, Instant.parse("2030-01-01T00:00:00Z"), null);
  }
  private PromptVersion prompt(UUID id, String content) { return new PromptVersion(UUID.randomUUID(), id, 1, 1, content, Instant.EPOCH); }
  private AgentConfiguration config(UUID id) { return new AgentConfiguration(UUID.randomUUID(), id, 1, RuntimeType.LLM,
      new ModelConfiguration("vendor", "model", false, 0, 10, 1000, null, "JSON_MODE", false), new MemoryPolicy(MemoryMode.STATELESS), Instant.EPOCH); }

  @Test void buildsFourOrderedMessagesForBothModesAndCountsUtf8Bytes() {
    var builder = new StatelessContextBuilder(new DecisionInputReader(), 10000);
    for (String mode : new String[] {"ACTION_SELECTION", "STRUCTURED_ACTION"}) {
      var context = builder.build(request(mode, "{\"board\":\"棋\"}"), prompt(agent, "策略 \"中文\""), config(agent));
      assertEquals("stateless-v1", context.builderVersion());
      assertEquals(java.util.List.of("system", "system", "user", "user"), context.messages().stream().map(ModelMessage::role).toList());
      assertTrue(context.messages().get(0).text().contains("selectedActionId"));
      assertTrue(context.messages().get(0).text().contains("STRUCTURED_ACTION"));
      assertTrue(context.messages().get(2).text().contains("\\\"中文\\\""));
      assertEquals(context.messages().stream().mapToInt(m -> m.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).sum(), context.utf8Bytes());
    }
  }

  @Test void serializesMessageFieldsInExplicitOrder() {
    var builder = new StatelessContextBuilder(new DecisionInputReader(), 10000);
    for (String mode : new String[] {"ACTION_SELECTION", "STRUCTURED_ACTION"}) {
      var context = builder.build(request(mode, "{\"marker\":\"棋\"}"),
          prompt(agent, "strategy"), config(agent));
      assertEquals("{\"rulesText\":\"rules\",\"actionSchema\":{\"type\":\"object\"},\"mode\":\""
          + mode + "\"}", context.messages().get(1).text());
      assertEquals("{\"playerPrompt\":\"strategy\"}", context.messages().get(2).text());
      assertEquals("{\"observation\":{\"marker\":\"棋\"},\"options\":[{\"selectedActionId\":\"x\",\"action\":{}}],\"constraints\":{}}",
          context.messages().get(3).text());
    }
  }

  @Test void ignoresUnplannedSpecAndKeepsRequestMaterialsOutOfModelMessages() {
    String rawSpec = "{\"mode\":\"ACTION_SELECTION\",\"options\":[{\"selectedActionId\":\"candidate\",\"action\":{}}],\"constraints\":{\"limit\":7},\"spec\":{\"unplanned\":true}}";
    var request = new DecisionRequest("1.0", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 7, 8,
        "game-secret", "rules-secret", "schema-secret", "{\"schemaOnly\":true}", rawSpec, "rules text", "{\"board\":\"opaque\"}",
        Instant.parse("2030-01-01T00:00:00Z"), "agent-version-secret");
    var prompt = prompt(agent, "player instruction");
    var config = new AgentConfiguration(UUID.randomUUID(), agent, 1, RuntimeType.LLM,
        new ModelConfiguration("private-provider", "private-model", false, 0, 10, 1000, "credential-reference", "JSON_MODE", false),
        new MemoryPolicy(MemoryMode.STATELESS), Instant.EPOCH);
    String savedSpec = request.actionSpecificationJson();
    var messages = new StatelessContextBuilder(new DecisionInputReader(), 10000).build(request, prompt, config).messages();
    String allMessages = messages.stream().map(ModelMessage::text).reduce("", String::concat);
    assertTrue(allMessages.contains("player instruction"));
    assertTrue(allMessages.contains("opaque"));
    assertTrue(allMessages.contains("candidate"));
    assertTrue(allMessages.contains("limit"));
    assertFalse(allMessages.contains("unplanned"));
    assertFalse(allMessages.contains("game-secret"));
    assertFalse(allMessages.contains("rules-secret"));
    assertFalse(allMessages.contains("schema-secret"));
    assertFalse(allMessages.contains("private-provider"));
    assertFalse(allMessages.contains("private-model"));
    assertFalse(allMessages.contains("credential-reference"));
    assertFalse(allMessages.contains(request.decisionId().toString()));
    assertFalse(allMessages.contains(request.matchId().toString()));
    assertFalse(allMessages.contains(request.participantId().toString()));
    assertFalse(allMessages.contains(request.turnId().toString()));
    assertEquals(savedSpec, request.actionSpecificationJson());
    assertEquals("player instruction", prompt.content());
    assertEquals("private-model", config.model().modelId());
  }

  @Test void sameFixedInputIsDeterministicAndInterleavedConcurrentRequestsStayIsolated() {
    var builder = new StatelessContextBuilder(new DecisionInputReader(), 10000);
    var requestA = request("ACTION_SELECTION", "{\"marker\":\"alpha\"}");
    var requestB = request("STRUCTURED_ACTION", "{\"marker\":\"beta\"}");
    var promptA = prompt(agent, "prompt-alpha");
    var promptB = prompt(agent, "prompt-beta");
    var config = config(agent);
    var baseline = builder.build(requestA, promptA, config);
    assertEquals(baseline, builder.build(requestA, promptA, config));
    var results = IntStream.range(0, 40).parallel().mapToObj(i -> i % 2 == 0
        ? builder.build(requestA, promptA, config)
        : builder.build(requestB, promptB, config)).toList();
    for (int i = 0; i < results.size(); i++) {
      var result = results.get(i);
      if (i % 2 == 0) {
        assertTrue(result.messages().get(2).text().contains("prompt-alpha"));
        assertTrue(result.messages().get(3).text().contains("alpha"));
        assertFalse(result.messages().get(3).text().contains("beta"));
      } else {
        assertTrue(result.messages().get(2).text().contains("prompt-beta"));
        assertTrue(result.messages().get(3).text().contains("beta"));
        assertFalse(result.messages().get(3).text().contains("alpha"));
      }
    }
  }
  @Test void enforcesInclusiveBudgetAndAgentBinding() {
    var input = request("ACTION_SELECTION", "{}"); var p = prompt(agent, "p"); var c = config(agent);
    var built = new StatelessContextBuilder(new DecisionInputReader(), 10000).build(input, p, c);
    assertDoesNotThrow(() -> new StatelessContextBuilder(new DecisionInputReader(), built.utf8Bytes()).build(input, p, c));
    assertEquals("INPUT_BUDGET_EXCEEDED", assertThrows(IllegalArgumentException.class, () -> new StatelessContextBuilder(new DecisionInputReader(), built.utf8Bytes()-1).build(input, p, c)).getMessage());
    assertEquals("AGENT_BINDING_MISMATCH", assertThrows(IllegalArgumentException.class, () -> new StatelessContextBuilder(new DecisionInputReader(), 10000).build(input, p, config(UUID.randomUUID()))).getMessage());
  }

  @Test void buildsContextWhenSpecificationOmitsRedundantActionSchemaId() {
    var request = new DecisionRequest("1.0", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 1,
        "sample", "v1", "schema", "{}",
        "{\"mode\":\"ACTION_SELECTION\",\"options\":[{\"selectedActionId\":\"option-id\",\"action\":{}}],\"constraints\":{}}",
        "rules", "{}", Instant.parse("2030-01-01T00:00:00Z"), null);
    var context = new StatelessContextBuilder(new DecisionInputReader(), 10000)
        .build(request, prompt(agent, "prompt"), config(agent));
    assertTrue(context.messages().get(3).text().contains("option-id"));
  }
}
