package io.frayground.adapter.game.connectfour;
import io.frayground.domain.game.connectfour.ConnectFourDefinition;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
class ConnectFourActionSchemaProviderTest {
  @Test void independentTrees() {
    var provider = new ConnectFourActionSchemaProvider();
    var first = (ObjectNode) provider.schema(ConnectFourDefinition.SCHEMA_ID);
    first.remove("properties");
    assertThat(provider.schema(ConnectFourDefinition.SCHEMA_ID).has("properties")).isTrue();
    var schema = provider.schema(ConnectFourDefinition.SCHEMA_ID);
    assertThat(schema.get("type").stringValue()).isEqualTo("object");
    assertThat(schema.get("additionalProperties").booleanValue()).isFalse();
    assertThat(schema.get("properties").get("type").get("const").stringValue()).isEqualTo("place_disc");
    assertThat(schema.get("$schema").stringValue()).isEqualTo("https://json-schema.org/draft/2020-12/schema");
    assertThat(schema.get("properties").get("type").get("type").stringValue()).isEqualTo("string");
    assertThat(schema.get("properties").get("column").get("type").stringValue()).isEqualTo("integer");
    assertThat(schema.get("properties").get("column").get("minimum").intValue()).isZero();
    assertThat(schema.get("properties").get("column").get("maximum").intValue()).isEqualTo(6);
    assertThat(schema.get("required").toString()).isEqualTo("[\"type\",\"column\"]");
    assertThatThrownBy(()->provider.schema("unknown")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> provider.schema(null)).isInstanceOf(IllegalArgumentException.class);
    ((ObjectNode) schema.get("properties").get("column")).put("maximum", 0);
    assertThat(provider.schema(ConnectFourDefinition.SCHEMA_ID).get("properties").get("column").get("maximum").intValue()).isEqualTo(6);
  }
}
