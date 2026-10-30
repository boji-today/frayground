package io.frayground.adapter.model.deepseek;

import java.util.Optional;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Serializes DeepSeek chat bodies.
 */
public final class DeepSeekPayloads {
  // TODO: 评估直接用 JsonGenerator / JsonParser 写入字节缓冲，减少请求和响应的中间对象。

  public static final Set<String> SCHEMA_KEYWORDS = Set.of(
      "type",
      "properties",
      "required",
      "additionalProperties",
      "enum",
      "items",
      "description");

  private static final JsonMapper JSON = JsonMapper.builder()
      .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
      .changeDefaultPropertyInclusion(inclusion ->
          inclusion.withValueInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL))
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .build();

  private DeepSeekPayloads() {}

  public static Optional<JsonNode> schema(String json) {
    try {
      JsonNode node = JSON.readTree(json);
      if (!node.isObject()) {
        return Optional.empty();
      }
      return Optional.of(node);
    } catch (JacksonException exception) {
      return Optional.empty();
    }
  }

  public static String writeRequest(DeepSeekChatRequest request) {
    return JSON.writeValueAsString(request);
  }

  public static DeepSeekChatResponse readResponse(String json) {
    return JSON.readValue(json, DeepSeekChatResponse.class);
  }

  public static JsonNode supportedSchema(JsonNode schema) {
    return filterSchema(schema);
  }

  private static JsonNode filterSchema(JsonNode node) {
    if (node.isObject()) {
      ObjectNode copy = JSON.createObjectNode();
      for (var entry : node.properties()) {
        String key = entry.getKey();
        if (!SCHEMA_KEYWORDS.contains(key)) {
          continue;
        }
        copy.set(key, "properties".equals(key)
            ? filterProperties(entry.getValue())
            : filterSchema(entry.getValue()));
      }
      return copy;
    }
    if (node.isArray()) {
      ArrayNode copy = JSON.createArrayNode();
      for (int i = 0; i < node.size(); i++) {
        copy.add(filterSchema(node.get(i)));
      }
      return copy;
    }
    return node;
  }

  private static JsonNode filterProperties(JsonNode properties) {
    if (!properties.isObject()) {
      return filterSchema(properties);
    }
    ObjectNode copy = JSON.createObjectNode();
    for (var entry : properties.properties()) {
      copy.set(entry.getKey(), filterSchema(entry.getValue()));
    }
    return copy;
  }
}
