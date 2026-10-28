package io.frayground.agent.context;

import io.frayground.agent.AgentContextBuilder;
import io.frayground.agent.BuiltContext;
import io.frayground.agent.DecisionRequest;
import io.frayground.agent.ModelMessage;
import io.frayground.agent.input.DecisionInputReader;
import io.frayground.domain.strategy.AgentConfiguration;
import io.frayground.domain.strategy.PromptVersion;
import java.nio.charset.StandardCharsets;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/** Builds a bounded, stateless input for one fixed decision. */
public final class StatelessContextBuilder implements AgentContextBuilder {
  private final DecisionInputReader reader;
  private final int maxInputBytes;
  private final JsonMapper mapper = JsonMapper.builder().build();
  public StatelessContextBuilder(DecisionInputReader reader, int maxInputBytes) {
    if (reader == null || maxInputBytes < 0) throw new IllegalArgumentException("INVALID_BUILDER_CONFIG");
    this.reader = reader; this.maxInputBytes = maxInputBytes;
  }

  @Override public BuiltContext build(DecisionRequest request, PromptVersion prompt, AgentConfiguration configuration) {
    if (prompt == null || configuration == null || !prompt.agentId().equals(configuration.agentId())) throw new IllegalArgumentException("AGENT_BINDING_MISMATCH");
    DecisionInputReader.ParsedContext parsed = reader.readContext(request);
    String platform = "Return exactly one JSON object. For ACTION_SELECTION return only selectedActionId; for STRUCTURED_ACTION return the game action object.";
    String game, promptText, state;
    try {
      var gameFields = mapper.createObjectNode();
      gameFields.put("rulesText", request.rulesText());
      gameFields.set("actionSchema", parsed.schema());
      gameFields.put("mode", parsed.specification().mode());
      game = mapper.writeValueAsString(gameFields);

      var promptFields = mapper.createObjectNode();
      promptFields.put("playerPrompt", prompt.content());
      promptText = mapper.writeValueAsString(promptFields);

      var stateFields = mapper.createObjectNode();
      stateFields.set("observation", parsed.observation());
      stateFields.set("options", parsed.specification().options());
      stateFields.set("constraints", parsed.specification().root().get("constraints"));
      state = mapper.writeValueAsString(stateFields);
    } catch (Exception e) { throw new IllegalArgumentException("INVALID_DECISION_INPUT"); }
    List<ModelMessage> messages = List.of(new ModelMessage("system", platform), new ModelMessage("system", game), new ModelMessage("user", promptText), new ModelMessage("user", state));
    int bytes = messages.stream().mapToInt(m -> m.text().getBytes(StandardCharsets.UTF_8).length).sum();
    if (bytes > maxInputBytes) throw new IllegalArgumentException("INPUT_BUDGET_EXCEEDED");
    return new BuiltContext("stateless-v1", messages, bytes);
  }
}
