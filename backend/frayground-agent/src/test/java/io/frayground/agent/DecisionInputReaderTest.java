package io.frayground.agent;

import static org.junit.jupiter.api.Assertions.*;
import io.frayground.agent.input.DecisionInputReader;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class DecisionInputReaderTest {
  private final DecisionInputReader reader = new DecisionInputReader();
  private DecisionRequest request(String spec) {
    return request(spec, "{}", "{}");
  }
  private DecisionRequest request(String spec, String schema, String observation) {
    return new DecisionRequest("1.0", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0, 0,
        "sample", "v1", "schema", schema, spec, "rules", observation, Instant.parse("2030-01-01T00:00:00Z"), null);
  }
  @Test void readsBothModesAndKeepsCandidateOrder() {
    var selection = reader.readSpecification(request("{\"mode\":\"ACTION_SELECTION\",\"actionSchemaId\":\"schema\",\"options\":[{\"selectedActionId\":\"z\",\"action\":{}},{\"selectedActionId\":\"a\",\"action\":{}}],\"constraints\":{},\"spec\":{}}"));
    assertEquals("ACTION_SELECTION", selection.mode()); assertEquals("z", selection.options().get(0).get("selectedActionId").textValue());
    assertEquals("STRUCTURED_ACTION", reader.readSpecification(request("{\"mode\":\"STRUCTURED_ACTION\",\"actionSchemaId\":\"schema\",\"options\":[],\"constraints\":{}}" )).mode());
  }
  @Test void rejectsMalformedDuplicateTrailingAndDuplicateIds() {
    for (String spec : new String[] {"{bad}", "{\"mode\":\"ACTION_SELECTION\",\"mode\":\"STRUCTURED_ACTION\",\"actionSchemaId\":\"schema\",\"options\":[],\"constraints\":{}}", "{\"mode\":\"ACTION_SELECTION\",\"actionSchemaId\":\"schema\",\"options\":[],\"constraints\":{}} {}", "{\"mode\":\"ACTION_SELECTION\",\"actionSchemaId\":\"schema\",\"options\":[{\"selectedActionId\":\"x\",\"action\":{}},{\"selectedActionId\":\"x\",\"action\":{}}],\"constraints\":{}}"})
      assertEquals("INVALID_DECISION_INPUT", assertThrows(IllegalArgumentException.class, () -> reader.readSpecification(request(spec))).getMessage());
  }
  @Test void checksSchemaIdAndDoesNotRequireRedundantSpecId() {
    var withoutRedundantId = request("{\"mode\":\"ACTION_SELECTION\",\"options\":[],\"constraints\":{}} ");
    assertDoesNotThrow(() -> reader.readSpecification(withoutRedundantId));
    assertThrows(IllegalArgumentException.class, () -> reader.readSpecification(request("{\"mode\":\"ACTION_SELECTION\",\"actionSchemaId\":\"other\",\"options\":[],\"constraints\":{}}")));
    assertThrows(IllegalArgumentException.class, () -> reader.readSpecification(request("{\"mode\":\"ACTION_SELECTION\",\"actionSchemaId\":7,\"options\":[],\"constraints\":{}}")));
    assertDoesNotThrow(() -> reader.readContext(withoutRedundantId));
  }

  @Test void injectedNonStrictMapperStillRejectsDuplicateKeysAtEveryDepth() {
    var strictReader = new DecisionInputReader(JsonMapper.builder().build());
    String validSpec = "{\"mode\":\"ACTION_SELECTION\",\"options\":[{\"selectedActionId\":\"x\",\"action\":{}}],\"constraints\":{}}";
    assertThrows(IllegalArgumentException.class, () -> strictReader.readSpecification(request(
        "{\"mode\":\"ACTION_SELECTION\",\"mode\":\"ACTION_SELECTION\",\"options\":[],\"constraints\":{}}")));
    assertThrows(IllegalArgumentException.class, () -> strictReader.readSpecification(request(
        "{\"mode\":\"ACTION_SELECTION\",\"options\":[{\"selectedActionId\":\"x\",\"action\":{\"a\":1,\"a\":2}}],\"constraints\":{}}")));
    assertThrows(IllegalArgumentException.class, () -> strictReader.readContext(request(validSpec,
        "{\"nested\":{\"x\":1,\"x\":2}}", "{}")));
    assertThrows(IllegalArgumentException.class, () -> strictReader.readContext(request(validSpec,
        "{}", "{\"nested\":{\"x\":1,\"x\":2}}")));
  }

  @Test void rejectsInvalidRootFieldTypesBlankIdsAndNonObjectActions() {
    String[] invalidSpecs = {
        "{\"mode\":4,\"options\":[],\"constraints\":{}}",
        "{\"mode\":\"ACTION_SELECTION\",\"options\":{},\"constraints\":{}}",
        "{\"mode\":\"ACTION_SELECTION\",\"options\":[],\"constraints\":[]}",
        "{\"mode\":\"ACTION_SELECTION\",\"options\":[{\"selectedActionId\":\" \",\"action\":{}}],\"constraints\":{}}",
        "{\"mode\":\"ACTION_SELECTION\",\"options\":[{\"selectedActionId\":\"x\",\"action\":[] }],\"constraints\":{}}"
    };
    for (String spec : invalidSpecs) {
      assertEquals("INVALID_DECISION_INPUT", assertThrows(IllegalArgumentException.class,
          () -> reader.readSpecification(request(spec))).getMessage());
    }
    assertThrows(IllegalArgumentException.class, () -> reader.readContext(request(
        "{\"mode\":\"ACTION_SELECTION\",\"options\":[],\"constraints\":{}}", "[]", "{}")));
    assertThrows(IllegalArgumentException.class, () -> reader.readContext(request(
        "{\"mode\":\"ACTION_SELECTION\",\"options\":[],\"constraints\":{}}", "{}", "[]")));
  }
  @Test void mockChoosesFirstWithoutHostedBindingsAndIgnoresMalformedContextOnlyFields() {
    var runtime = new io.frayground.agent.runtime.MockAgentRuntime(reader, java.time.Clock.fixed(Instant.parse("2029-01-01T00:00:00Z"), java.time.ZoneOffset.UTC));
    var request = request("{\"mode\":\"ACTION_SELECTION\",\"actionSchemaId\":\"schema\",\"options\":[{\"selectedActionId\":\"odd-id\",\"action\":{}}],\"constraints\":{}} ");
    var outcome = runtime.decide(new RuntimeInput(request, Optional.empty()), () -> false).toCompletableFuture().join();
    assertEquals("odd-id", ((RuntimeOutcome.SelectedAction) outcome).selectedActionId());
  }
}
