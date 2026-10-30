package io.frayground.adapter.game;

import io.frayground.application.port.MatchFactory;
import java.time.Clock;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Adapter-layer assembly for registered games and initial match creation. */
@Configuration(proxyBeanMethods = false)
public class GameAdapterConfiguration {
  @Bean
  GameRegistry gameRegistry(ObjectProvider<GameRegistration<?, ?, ?>> registrations) {
    return new DefaultGameRegistry(registrations.orderedStream().toList());
  }

  @Bean
  MatchFactory matchFactory(GameRegistry registry) {
    return new RegisteredMatchFactory(registry, JsonMapper.builder().build(),
        Clock.systemUTC(), UUID::randomUUID);
  }
}
