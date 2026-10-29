package io.frayground.adapter.game.blackjack;

import io.frayground.adapter.game.GameRegistration;
import io.frayground.domain.game.blackjack.BlackjackDefinition;
import io.frayground.domain.game.blackjack.FisherYatesShuffler;
import java.security.SecureRandom;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "frayground.games.blackjack", name = "enabled",
    havingValue = "true", matchIfMissing = false)
public class BlackjackGameConfiguration {
  @Bean
  BlackjackDefinition blackjackDefinition() {
    SecureRandom random = new SecureRandom();
    return new BlackjackDefinition(new FisherYatesShuffler(random::nextLong));
  }

  @Bean
  BlackjackCodec blackjackCodec() {
    return new BlackjackCodec();
  }

  @Bean
  BlackjackActionSchemaProvider blackjackActionSchemaProvider() {
    return new BlackjackActionSchemaProvider();
  }

  @Bean
  GameRegistration<io.frayground.domain.game.blackjack.BlackjackState,
      io.frayground.domain.game.blackjack.BlackjackAction,
      io.frayground.domain.game.blackjack.BlackjackObservation> blackjackGameRegistration(
          BlackjackDefinition definition, BlackjackCodec codec, BlackjackActionSchemaProvider schemaProvider) {
    return new GameRegistration<>(definition, codec, schemaProvider);
  }

  @Bean
  BlackjackDecisionMetadata blackjackDecisionMetadata() {
    return new BlackjackDecisionMetadata();
  }

  @Bean
  BlackjackSystemPhaseHandler blackjackSystemPhaseHandler(BlackjackDefinition definition) {
    return new BlackjackSystemPhaseHandler(definition);
  }
}
