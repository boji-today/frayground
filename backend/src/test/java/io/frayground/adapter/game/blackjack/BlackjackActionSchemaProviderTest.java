package io.frayground.adapter.game.blackjack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.frayground.domain.game.blackjack.BlackjackDefinition;
import org.junit.jupiter.api.Test;

class BlackjackActionSchemaProviderTest {
  @Test void schemaAcceptsOnlyHitAndStand() {
    var schema = new BlackjackActionSchemaProvider().schema(BlackjackDefinition.SCHEMA_ID).toString();
    assertThat(schema).contains("hit").contains("stand").contains("additionalProperties");
    assertThatThrownBy(() -> new BlackjackActionSchemaProvider().schema("other"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
