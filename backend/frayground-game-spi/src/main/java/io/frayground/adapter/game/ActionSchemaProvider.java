package io.frayground.adapter.game;

import tools.jackson.databind.JsonNode;

/**
 * Supplies the JSON Schema registered for one {@code schemaId}.
 *
 * <p>The schema stays outside the rules. An action specification refers to it
 * by id and does not embed a remote schema URL.
 */
public interface ActionSchemaProvider {
  JsonNode schema(String schemaId);
}
