package io.frayground.agent.input;

import io.frayground.agent.DecisionRequest;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import tools.jackson.core.JsonParser;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Parses only the public JSON shapes needed to build a decision context or choose a candidate. */
public final class DecisionInputReader {
  private final ObjectMapper mapper;
  public DecisionInputReader() {
    this(JsonMapper.builder().build());
  }

  /** Retains mapper customizations while enforcing duplicate detection on a private copy. */
  public DecisionInputReader(ObjectMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper).rebuild()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .build();
  }

  public ParsedContext readContext(DecisionRequest request) {
    try {
      return new ParsedContext(
          parseObject(request.actionSchemaJson()),
          parseObject(request.observationJson()),
          readSpecification(request));
    } catch (RuntimeException e) {
      throw invalid();
    }
  }

  public ParsedSpecification readSpecification(DecisionRequest request) {
    try {
      JsonNode root = parseObject(request.actionSpecificationJson());
      JsonNode mode = root.get("mode");
      JsonNode schemaId = root.get("actionSchemaId");
      JsonNode options = root.get("options");
      JsonNode constraints = root.get("constraints");
      if (mode == null || !mode.isTextual()
          || !(mode.textValue().equals("ACTION_SELECTION") || mode.textValue().equals("STRUCTURED_ACTION"))
          || options == null || !options.isArray()
          || constraints == null || !constraints.isObject()) {
        throw invalid();
      }
      if (schemaId != null && (!schemaId.isTextual()
          || !request.actionSchemaId().equals(schemaId.textValue()))) {
        throw invalid();
      }
      Set<String> ids = new HashSet<>();
      for (JsonNode option : options) {
        if (!option.isObject()) throw invalid();
        JsonNode id = option.get("selectedActionId");
        JsonNode action = option.get("action");
        if (id == null || !id.isTextual() || id.textValue().isBlank()
            || !ids.add(id.textValue()) || action == null || !action.isObject()) {
          throw invalid();
        }
      }
      return new ParsedSpecification(root, mode.textValue(), options);
    } catch (RuntimeException e) {
      throw invalid();
    }
  }

  private JsonNode parseObject(String json) {
    try (JsonParser parser = mapper.createParser(json)) {
      JsonNode node = mapper.readTree(parser);
      if (node == null || !node.isObject() || parser.nextToken() != null) throw invalid();
      return node;
    } catch (Exception e) {
      throw invalid();
    }
  }
  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("INVALID_DECISION_INPUT");
  }
  public record ParsedContext(JsonNode schema, JsonNode observation, ParsedSpecification specification) {}
  public record ParsedSpecification(JsonNode root, String mode, JsonNode options) {}
}
