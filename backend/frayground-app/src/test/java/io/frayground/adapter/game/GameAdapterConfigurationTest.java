package io.frayground.adapter.game;

import static org.assertj.core.api.Assertions.assertThat;

import io.frayground.adapter.game.connectfour.ConnectFourActionSchemaProvider;
import io.frayground.adapter.game.connectfour.ConnectFourCodec;
import io.frayground.application.port.MatchFactory;
import io.frayground.domain.game.connectfour.ConnectFourDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

class GameAdapterConfigurationTest {
  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withUserConfiguration(GameAdapterConfiguration.class);

  @Test void assemblesWithZeroRegistrations() {
    runner.run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context.getBean(GameRegistry.class)).isInstanceOf(DefaultGameRegistry.class);
      assertThat(context.getBean(MatchFactory.class)).isNotNull();
    });
  }

  @Test void collectsRegistrationsAndRejectsDuplicateKeys() {
    runner.withUserConfiguration(OneGame.class).run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context.getBean(GameRegistry.class).registration(
          new ConnectFourDefinition().key())).isPresent();
    });
    runner.withUserConfiguration(DuplicateGames.class).run(context ->
        assertThat(context).hasFailed());
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class OneGame {
    @Bean GameRegistration<?, ?, ?> one() {
      return registration();
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class DuplicateGames {
    @Bean GameRegistration<?, ?, ?> one() { return registration(); }
    @Bean GameRegistration<?, ?, ?> two() { return registration(); }
  }

  private static GameRegistration<?, ?, ?> registration() {
    return new GameRegistration<>(new ConnectFourDefinition(), new ConnectFourCodec(),
        new ConnectFourActionSchemaProvider());
  }
}
