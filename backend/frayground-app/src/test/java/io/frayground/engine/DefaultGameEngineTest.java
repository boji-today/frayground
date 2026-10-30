package io.frayground.engine;

import static org.junit.jupiter.api.Assertions.*;

import io.frayground.domain.game.Participant;
import io.frayground.domain.game.connectfour.ConnectFourDefinition;
import io.frayground.domain.game.connectfour.PlaceDisc;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DefaultGameEngineTest {
  private final ConnectFourDefinition rules = new ConnectFourDefinition();
  private final UUID a = UUID.randomUUID();
  private final UUID b = UUID.randomUUID();

  @Test void legalActionIsResolvedByRules() {
    var state = rules.initialize(List.of(new Participant(a, UUID.randomUUID(), "A"),
        new Participant(b, UUID.randomUUID(), "B")));
    var result = new DefaultGameEngine().apply(rules, state, Map.of(a, new PlaceDisc("place_disc", 0)));
    assertEquals(1, result.newState().moveCount());
    assertEquals(0, state.moveCount());
  }

  @Test void rejectedActionCarriesRuleCodeAndDoesNotResolve() {
    var state = rules.initialize(List.of(new Participant(a, UUID.randomUUID(), "A"),
        new Participant(b, UUID.randomUUID(), "B")));
    var error = assertThrows(GameActionRejectedException.class,
        () -> new DefaultGameEngine().apply(rules, state, Map.of(b, new PlaceDisc("place_disc", 0))));
    assertEquals("NOT_YOUR_TURN", error.code());
    assertEquals(0, state.moveCount());
  }
}
