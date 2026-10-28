package io.frayground.adapter.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.frayground.adapter.game.connectfour.ConnectFourActionSchemaProvider;
import io.frayground.adapter.game.connectfour.ConnectFourCodec;
import io.frayground.domain.game.GameKey;
import io.frayground.domain.game.connectfour.ConnectFourDefinition;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DefaultGameRegistryTest {
  private GameRegistration<?, ?, ?> registration() {
    return new GameRegistration<>(new ConnectFourDefinition(), new ConnectFourCodec(),
        new ConnectFourActionSchemaProvider());
  }

  @Test void indexesCompleteKeyAndQueriesDelegateToRegistration() {
    var entry = registration();
    var registry = new DefaultGameRegistry(List.of(entry));
    var key = new GameKey("connect-four", "1");
    assertThat(registry.registration(key)).contains(entry);
    assertThat(registry.definition(key)).contains(entry.definition());
    assertThat(registry.codec(key)).contains(entry.codec());
    assertThat(registry.schemaProvider(key)).contains(entry.schemaProvider());
    assertThat(registry.registration(new GameKey("connect-four", "2"))).isEmpty();
  }

  @Test void acceptsEmptyAndCopiesRegistrationCollection() {
    assertThat(new DefaultGameRegistry(List.of()).registration(
        new GameKey("connect-four", "1"))).isEmpty();
    var entries = new ArrayList<GameRegistration<?, ?, ?>>();
    var entry = registration();
    entries.add(entry);
    var registry = new DefaultGameRegistry(entries);
    entries.clear();
    assertThat(registry.registration(entry.definition().key())).contains(entry);
  }

  @Test void rejectsDuplicateCompleteKey() {
    assertThatThrownBy(() -> new DefaultGameRegistry(List.of(registration(), registration())))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
