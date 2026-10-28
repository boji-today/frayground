package io.frayground.adapter.game.connectfour;

import io.frayground.adapter.game.GameRegistration;
import io.frayground.domain.game.connectfour.ConnectFourDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring self-registration for Connect Four, disabled unless explicitly enabled. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "frayground.games.connect-four", name = "enabled",
    havingValue = "true", matchIfMissing = false)
public class ConnectFourGameConfiguration {
  @Bean
  ConnectFourDefinition connectFourDefinition() {
    return new ConnectFourDefinition();
  }

  @Bean
  ConnectFourCodec connectFourCodec() {
    return new ConnectFourCodec();
  }

  @Bean
  ConnectFourActionSchemaProvider connectFourActionSchemaProvider() {
    return new ConnectFourActionSchemaProvider();
  }

  @Bean
  GameRegistration<io.frayground.domain.game.connectfour.ConnectFourState,
      io.frayground.domain.game.connectfour.PlaceDisc,
      io.frayground.domain.game.connectfour.ConnectFourObservation> connectFourGameRegistration(
          ConnectFourDefinition definition, ConnectFourCodec codec,
          ConnectFourActionSchemaProvider schemaProvider) {
    return new GameRegistration<>(definition, codec, schemaProvider);
  }
}
