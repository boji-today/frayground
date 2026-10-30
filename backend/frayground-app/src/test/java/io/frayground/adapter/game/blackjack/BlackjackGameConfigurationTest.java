package io.frayground.adapter.game.blackjack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.frayground.adapter.game.GameAdapterConfiguration;
import io.frayground.adapter.game.GameRegistry;
import io.frayground.adapter.game.SystemPhaseHandler;
import io.frayground.application.port.DecisionGameMetadata;
import io.frayground.application.port.MatchFactory;
import io.frayground.domain.game.blackjack.BlackjackDefinition;
import io.frayground.domain.game.Participant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class BlackjackGameConfigurationTest {
  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withUserConfiguration(GameAdapterConfiguration.class, BlackjackGameConfiguration.class);

  @Test void explicitlyEnabledRegistersGameWithoutAnInitialTurn() {
    runner.withPropertyValues("frayground.games.blackjack.enabled=true").run(context -> {
      assertThat(context).hasSingleBean(BlackjackDefinition.class);
      assertThat(context).hasSingleBean(SystemPhaseHandler.class);
      assertThat(context).hasSingleBean(DecisionGameMetadata.class);
      var participant = new Participant(UUID.randomUUID(), UUID.randomUUID(), "P1");
      var created = context.getBean(MatchFactory.class).create(UUID.randomUUID(),
          BlackjackDefinition.KEY, List.of(participant));
      assertThat(created.firstTurn()).isNull();
      assertThat(created.match().currentTurnId()).isNull();
    });
  }

  @Test void falseOrMissingLeavesGameUnregistered() {
    runner.withPropertyValues("frayground.games.blackjack.enabled=false").run(context ->
        assertThat(context.getBean(GameRegistry.class).registration(BlackjackDefinition.KEY)).isEmpty());
    runner.run(context -> assertThatThrownBy(() -> context.getBean(MatchFactory.class).create(
        UUID.randomUUID(), BlackjackDefinition.KEY, List.of())).isInstanceOf(IllegalArgumentException.class));
  }

  @Test void applicationYamlLeavesBlackjackDisabled() throws Exception {
    String yaml = new String(getClass().getResourceAsStream("/application.yml").readAllBytes(),
        java.nio.charset.StandardCharsets.UTF_8);
    assertThat(yaml).contains("blackjack:").contains("enabled: false");
  }
}
