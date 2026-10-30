package io.frayground.adapter.game.blackjack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.frayground.domain.game.blackjack.BlackjackDefinition;
import io.frayground.domain.game.blackjack.Card;
import io.frayground.domain.game.blackjack.ShoeShuffle;
import io.frayground.domain.game.Participant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class BlackjackCodecTest {
  private final BlackjackCodec codec = new BlackjackCodec();

  @Test void roundTripsReadyAndDealtStateAndRejectsExtraFields() {
    var game = new BlackjackDefinition(() -> new ShoeShuffle(4, shoe(new Card(0, "CLUBS", "10"),
        new Card(0, "CLUBS", "9"), new Card(0, "CLUBS", "6"), new Card(0, "CLUBS", "7"))));
    var participant = new Participant(UUID.randomUUID(), UUID.randomUUID(), "P1");
    var ready = game.initialize(List.of(participant));
    assertThat(codec.decodeState(codec.encodeState(ready))).isEqualTo(ready);
    var dealt = game.resolveSystem(ready).orElseThrow().newState();
    assertThat(codec.decodeState(codec.encodeState(dealt))).isEqualTo(dealt);
    assertThat(codec.decodeAction(codec.encodeAction(new io.frayground.domain.game.blackjack.BlackjackAction("hit"))).type())
        .isEqualTo("hit");
    ObjectNode extra = (ObjectNode) codec.encodeState(ready);
    extra.put("future", 1);
    assertThatThrownBy(() -> codec.decodeState(extra)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> codec.decodeAction(codec.encodeState(ready))).isInstanceOf(IllegalArgumentException.class);
  }

  private static List<Card> shoe(Card... prefix) {
    List<Card> cards = new ArrayList<>(io.frayground.domain.game.blackjack.BlackjackRules.standardShoe());
    for (int i = 0; i < prefix.length; i++) {
      Collections.swap(cards, i, cards.indexOf(prefix[i]));
    }
    return cards;
  }
}
