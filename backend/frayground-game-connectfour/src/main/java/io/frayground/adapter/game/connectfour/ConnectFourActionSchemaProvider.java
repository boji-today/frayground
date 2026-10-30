package io.frayground.adapter.game.connectfour;

import io.frayground.adapter.game.ActionSchemaProvider;
import io.frayground.domain.game.connectfour.ConnectFourDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class ConnectFourActionSchemaProvider implements ActionSchemaProvider {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String SCHEMA = """
      {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
       "properties":{"type":{"type":"string","const":"place_disc"},
       "column":{"type":"integer","minimum":0,"maximum":6}},
       "required":["type","column"],"additionalProperties":false}
      """;
  @Override
  public JsonNode schema(String schemaId) {
    if (!ConnectFourDefinition.SCHEMA_ID.equals(schemaId)) {
      throw new IllegalArgumentException("schemaId");
    }
    return JSON.readTree(SCHEMA);
  }
}
