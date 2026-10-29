package io.frayground.agent.runtime;

import io.frayground.agent.ModelInvocationResult;
import io.frayground.agent.RuntimeError;
import io.frayground.agent.RuntimeOutcome;
import io.frayground.agent.ToolArguments;
import java.util.HashSet;
import java.util.Set;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Builds and strictly parses the fixed ACTION_SELECTION output contract. */
public final class SelectionOutputCodec {
  private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

  public String schema(Iterable<String> candidates) {
    var ids = new java.util.ArrayList<String>();
    candidates.forEach(ids::add);
    if (ids.isEmpty() || ids.stream().anyMatch(s -> s == null || s.isBlank()) || new HashSet<>(ids).size() != ids.size())
      throw new IllegalArgumentException("INVALID_ACTION_OPTIONS");
    var root = json.createObjectNode(); root.put("type", "object");
    var properties = root.putObject("properties"); var selected = properties.putObject("selectedActionId");
    selected.put("type", "string"); var values = selected.putArray("enum"); ids.forEach(values::add);
    root.putArray("required").add("selectedActionId"); root.put("additionalProperties", false);
    return json.writeValueAsString(root);
  }

  public RuntimeOutcome parse(ModelInvocationResult result, String mode, Set<String> candidates) {
    if (result == null) return failed("MODEL_OUTPUT_EMPTY");
    if (result.error() != null) return new RuntimeOutcome.Failed(result.error());
    String raw;
    if ("JSON_MODE".equals(mode)) {
      if (!result.toolArguments().isEmpty()) return failed("MODEL_OUTPUT_INVALID");
      raw = result.content();
    } else if ("STRICT_TOOL".equals(mode)) {
      if (result.toolArguments().size() != 1) return failed("MODEL_OUTPUT_INVALID");
      ToolArguments tool = result.toolArguments().get(0);
      if (!"submit_action".equals(tool.name()) || (result.content() != null && !result.content().isBlank())) return failed("MODEL_OUTPUT_INVALID");
      raw = tool.argumentsJson();
    } else return failed("MODEL_CONFIGURATION_INVALID");
    if (raw == null || raw.isBlank() || raw.contains("``")) return failed("MODEL_OUTPUT_INVALID");
    try (var parser = json.createParser(raw)) {
      JsonNode node = json.readTree(parser);
      if (node == null || !node.isObject() || parser.nextToken() != null || node.size() != 1) return failed("MODEL_OUTPUT_INVALID");
      JsonNode id = node.get("selectedActionId");
      if (id == null || !id.isTextual() || !candidates.contains(id.textValue())) return failed("MODEL_OUTPUT_INVALID");
      return new RuntimeOutcome.SelectedAction(id.textValue());
    } catch (Exception e) { return failed("MODEL_OUTPUT_INVALID"); }
  }
  private static RuntimeOutcome failed(String code) { return new RuntimeOutcome.Failed(new RuntimeError(code, code)); }
}
