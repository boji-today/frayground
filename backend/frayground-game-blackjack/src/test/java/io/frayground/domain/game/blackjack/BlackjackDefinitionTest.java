package io.frayground.domain.game.blackjack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.frayground.domain.game.GameEvent;
import io.frayground.domain.game.GameOutcome;
import io.frayground.domain.game.Participant;
import io.frayground.domain.game.ValidationResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BlackjackDefinitionTest {
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private final UUID first = UUID.randomUUID();
  private final UUID second = UUID.randomUUID();
  private final Participant p1 = new Participant(first, UUID.randomUUID(), "P1");
  private final Participant p2 = new Participant(second, UUID.randomUUID(), "P2");

  @Test void readyMatchHasNoTurnAndDoesNotShuffle() {
    var game = game(new Card(0, "CLUBS", "A"));
    var state = game.initialize(List.of(p2, p1));
    assertThat(state.phase()).isEqualTo(BlackjackPhase.READY);
    assertThat(state.participantOrder()).containsExactly(first, second);
    assertThat(state.shoe()).isEmpty();
    assertThat(game.nextTurn(state, List.of(p1, p2)).requiredParticipants()).isEmpty();
    assertThat(game.observe(state, first).dealerCards()).isEmpty();
    assertThat(game.actions(state, first).options()).isEmpty();
    assertThat(game.validate(state, null, null)).isEqualTo(new ValidationResult.Rejected("UNKNOWN_PARTICIPANT"));
    assertThat(game.validate(state, first, null)).isEqualTo(new ValidationResult.Rejected("GAME_FINISHED"));
    assertThatThrownBy(() -> game.initialize(List.of())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> game.initialize(List.of(new Participant(first, UUID.randomUUID(), "A"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test void dealerNaturalFinishesWithoutADecision() {
    var game = game(card(0, "10"), card(0, "A"), card(0, "9"), card(0, "K"));
    var dealt = game.resolveSystem(game.initialize(List.of(p1))).orElseThrow();
    assertThat(dealt.result().status()).isEqualTo(GameOutcome.DRAW);
    assertThat(dealt.result().standings()).singleElement().satisfies(standing -> {
      assertThat(standing.rank()).isEqualTo(1);
      assertThat(standing.score()).isEqualTo(-1);
    });
    assertThat(dealt.newState().currentParticipantId()).isNull();
    assertThat(dealt.newState().holeHidden()).isFalse();
    assertThat(game.nextTurn(dealt.newState(), List.of(p1)).requiredParticipants()).isEmpty();
    assertThat(game.observe(dealt.newState(), first).dealerCards()).extracting(BlackjackObservation.VisibleCard::rank)
        .containsExactly("A", "K");
  }

  @Test void playerNaturalPaysThreeToTwo() {
    var game = game(card(0, "A"), card(0, "9"), card(0, "K"), card(0, "8"));
    var dealt = game.resolveSystem(game.initialize(List.of(p1))).orElseThrow();
    assertThat(dealt.result().status()).isEqualTo(GameOutcome.WIN);
    assertThat(dealt.result().standings().get(0).score()).isEqualTo(1.5);
    assertThat(types(dealt)).contains("ShoeShuffled", "DealerHoleRevealed", "MatchFinished");
    var history = history(dealt.events());
    assertThat(game.visibleHistory(first, history)).noneMatch(event -> event.type().equals("ShoeShuffled"));
    assertThat(game.visibleHistory(first, history).stream().filter(event -> event.type().equals("CardDealt"))
        .map(event -> event.payload().get("rank")).toList()).contains("K");
  }

  @Test void laterPlayerSeesPriorActionAndHoleStaysHiddenUntilReveal() {
    var game = game(card(0, "A"), card(0, "10"), card(0, "9"), card(0, "K"), card(0, "6"), card(0, "7"), card(0, "5"));
    var dealt = game.resolveSystem(game.initialize(List.of(p1, p2))).orElseThrow().newState();
    assertThat(dealt.currentParticipantId()).isEqualTo(second);
    assertThat(game.observe(dealt, second).players().get(0).status()).isEqualTo(PlayerStatus.NATURAL);
    assertThat(game.observe(dealt, second).dealerCards()).extracting(BlackjackObservation.VisibleCard::rank)
        .containsExactly("9");
    var hidden = game.visibleHistory(second, history(game.resolveSystem(game.initialize(List.of(p1, p2))).orElseThrow().events()));
    assertThat(hidden).anyMatch(event -> Boolean.TRUE.equals(event.payload().get("hidden")));
    assertThat(hidden).noneMatch(event -> "7".equals(event.payload().get("rank")));
    var stood = game.resolve(dealt, Map.of(second, new BlackjackAction("stand"))).newState();
    var finished = game.resolveSystem(stood).orElseThrow();
    assertThat(finished.newState().dealerCards()).hasSize(3);
    assertThat(finished.result().standings()).extracting("score").containsExactly(1.5, -1.0);
    assertThat(finished.result().status()).isEqualTo(GameOutcome.WIN);
  }

  @Test void hitBelowTwentyOneContinuesAndSoftSeventeenStands() {
    var game = game(card(0, "10"), card(0, "A"), card(0, "6"), card(1, "6"), card(0, "3"), card(0, "K"));
    var state = game.resolveSystem(game.initialize(List.of(p1))).orElseThrow().newState();
    assertThat(game.validate(state, second, new BlackjackAction("hit")))
        .isEqualTo(new ValidationResult.Rejected("UNKNOWN_PARTICIPANT"));
    assertThat(game.validate(state, first, new BlackjackAction("fold")))
        .isEqualTo(new ValidationResult.Rejected("INVALID_ACTION"));
    var hit = game.resolve(state, Map.of(first, new BlackjackAction("hit")));
    assertThat(hit.newState().currentParticipantId()).isEqualTo(first);
    assertThat(types(hit)).contains("PlayerHit").doesNotContain("PlayerBust");
    var stood = game.resolve(hit.newState(), Map.of(first, new BlackjackAction("stand"))).newState();
    var finished = game.resolveSystem(stood).orElseThrow().newState();
    assertThat(finished.dealerCards()).extracting(Card::rank).containsExactly("A", "6");
    assertThat(finished.result().standings().get(0).score()).isEqualTo(1);
    assertThat(finished.result().status()).isEqualTo(GameOutcome.WIN);
  }

  @Test void bustedTableRevealsHoleWithoutDealerDrawAndTiesForLast() {
    var game = game(card(0, "10"), card(1, "10"), card(0, "6"), card(1, "6"), card(0, "K"), card(0, "A"));
    var state = game.resolveSystem(game.initialize(List.of(p1))).orElseThrow().newState();
    var busted = game.resolve(state, Map.of(first, new BlackjackAction("hit"))).newState();
    var finished = game.resolveSystem(busted).orElseThrow();
    assertThat(types(finished)).contains("DealerHoleRevealed").doesNotContain("DealerHit", "DealerStand");
    assertThat(finished.newState().dealerCards()).hasSize(2);
    assertThat(finished.result().status()).isEqualTo(GameOutcome.DRAW);
    assertThat(finished.result().standings().get(0).rank()).isEqualTo(1);
    assertThat(finished.result().standings().get(0).score()).isEqualTo(-1);
  }

  @Test void equalPositiveScoresAreADraw() {
    var game = game(card(0, "10"), card(1, "10"), card(0, "9"), card(0, "K"), card(1, "9"), card(0, "6"), card(1, "K"));
    var dealt = game.resolveSystem(game.initialize(List.of(p1, p2))).orElseThrow().newState();
    var firstStood = game.resolve(dealt, Map.of(first, new BlackjackAction("stand"))).newState();
    var finished = game.resolveSystem(game.resolve(firstStood, Map.of(second, new BlackjackAction("stand"))).newState()).orElseThrow();
    assertThat(finished.newState().dealerCards()).hasSize(3);
    assertThat(finished.result().status()).isEqualTo(GameOutcome.DRAW);
    assertThat(finished.result().standings()).allSatisfy(standing -> {
      assertThat(standing.rank()).isEqualTo(1);
      assertThat(standing.score()).isEqualTo(1);
    });
  }

  private BlackjackDefinition game(Card... prefix) {
    List<Card> shoe = front(prefix);
    return new BlackjackDefinition(() -> new ShoeShuffle(11, shoe));
  }

  private static Card card(int deck, String rank) {
    return new Card(deck, "CLUBS", rank);
  }

  private static List<Card> front(Card... prefix) {
    List<Card> cards = new ArrayList<>(BlackjackRules.standardShoe());
    for (int i = 0; i < prefix.length; i++) {
      int found = -1;
      for (int j = i; j < cards.size(); j++) {
        if (cards.get(j).equals(prefix[i])) {
          found = j;
          break;
        }
      }
      if (found < 0) {
        throw new IllegalStateException(prefix[i].toString());
      }
      Collections.swap(cards, i, found);
    }
    return List.copyOf(cards);
  }

  private static List<String> types(io.frayground.domain.game.Resolution<?> resolution) {
    return resolution.events().stream().map(event -> event.type()).toList();
  }

  private static List<GameEvent> history(List<io.frayground.domain.game.GameEventDraft> drafts) {
    UUID match = UUID.randomUUID();
    List<GameEvent> events = new ArrayList<>();
    for (int i = 0; i < drafts.size(); i++) {
      var draft = drafts.get(i);
      events.add(new GameEvent(match, i + 1L, 1, null, draft.type(), draft.eventVersion(), draft.payload(), NOW));
    }
    return events;
  }
}
