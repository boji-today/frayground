package io.frayground.adapter.game.connectfour;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.frayground.adapter.game.GameAdapterConfiguration;
import io.frayground.adapter.game.GameRegistry;
import io.frayground.application.port.MatchFactory;
import io.frayground.domain.game.GameKey;
import io.frayground.domain.game.Participant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ConnectFourGameConfigurationTest {
  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withUserConfiguration(GameAdapterConfiguration.class, ConnectFourGameConfiguration.class);

  @Test void explicitlyEnabledRegistersAllBeansAndFactoryCanInitialize() {
    runner.withPropertyValues("frayground.games.connect-four.enabled=true").run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context).hasBean("connectFourDefinition");
      assertThat(context).hasBean("connectFourCodec");
      assertThat(context).hasBean("connectFourActionSchemaProvider");
      assertThat(context).hasBean("connectFourGameRegistration");
      var a = new Participant(UUID.randomUUID(), UUID.randomUUID(), "A");
      var b = new Participant(UUID.randomUUID(), UUID.randomUUID(), "B");
      assertThat(context.getBean(MatchFactory.class).create(UUID.randomUUID(),
          new GameKey("connect-four", "1"), List.of(a, b)).firstTurn().requiredParticipants())
          .containsExactly(a.participantId());
    });
  }

  @Test void falseOrMissingLeavesGameUnregistered() {
    for (String value : List.of("false")) {
      runner.withPropertyValues("frayground.games.connect-four.enabled=" + value).run(context -> {
        assertThat(context).hasNotFailed();
        assertThat(context).doesNotHaveBean("connectFourGameRegistration");
        assertThat(context.getBean(GameRegistry.class).registration(new GameKey("connect-four", "1")))
            .isEmpty();
      });
    }
    runner.run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context).doesNotHaveBean("connectFourGameRegistration");
      assertThatThrownBy(() -> context.getBean(MatchFactory.class).create(
          UUID.randomUUID(), new GameKey("connect-four", "1"), List.of()))
          .isInstanceOf(IllegalArgumentException.class);
    });
  }

  @Test void applicationYamlExplicitlyEnablesConnectFour() throws Exception {
    String yaml = new String(getClass().getResourceAsStream("/application.yml").readAllBytes(),
        java.nio.charset.StandardCharsets.UTF_8);
    assertThat(yaml).contains("connect-four:").contains("enabled: true");
  }
}
