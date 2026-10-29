package io.frayground.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.frayground.adapter.game.DefaultGameRegistry;
import io.frayground.adapter.game.GameRegistration;
import io.frayground.adapter.game.GameRegistry;
import io.frayground.adapter.game.SystemPhaseHandler;
import io.frayground.adapter.game.RegisteredMatchFactory;
import io.frayground.adapter.game.blackjack.BlackjackActionSchemaProvider;
import io.frayground.adapter.game.blackjack.BlackjackCodec;
import io.frayground.adapter.game.blackjack.BlackjackDecisionMetadata;
import io.frayground.adapter.game.blackjack.BlackjackSystemPhaseHandler;
import io.frayground.adapter.memory.InMemoryStorage;
import io.frayground.adapter.memory.InMemoryTransactionManager;
import io.frayground.application.port.DecisionGameMetadata;
import io.frayground.application.port.GameEventStore;
import io.frayground.application.port.MatchStore;
import io.frayground.application.port.RoomStore;
import io.frayground.application.port.SettlementStore;
import io.frayground.application.port.SnapshotStore;
import io.frayground.application.port.TurnStore;
import io.frayground.config.MemoryStorageConfiguration;
import io.frayground.domain.action.ActionEnvelope;
import io.frayground.domain.game.GameOutcome;
import io.frayground.domain.game.Participant;
import io.frayground.domain.game.blackjack.BlackjackDefinition;
import io.frayground.domain.game.blackjack.BlackjackRules;
import io.frayground.domain.game.blackjack.Card;
import io.frayground.domain.game.blackjack.ShoeShuffle;
import io.frayground.domain.game.blackjack.ShoeShuffler;
import io.frayground.domain.match.ExecutionMode;
import io.frayground.domain.match.ExecutionStatus;
import io.frayground.domain.match.Match;
import io.frayground.domain.match.MatchStatus;
import io.frayground.domain.match.Room;
import io.frayground.domain.match.RoomHealth;
import io.frayground.domain.strategy.AgentConfiguration;
import io.frayground.domain.strategy.MemoryMode;
import io.frayground.domain.strategy.MemoryPolicy;
import io.frayground.domain.strategy.ModelConfiguration;
import io.frayground.domain.strategy.PromptVersion;
import io.frayground.domain.strategy.RuntimeType;
import io.frayground.application.port.AgentBindingsReader;
import io.frayground.agent.AgentRuntime;
import io.frayground.agent.HostedBindings;
import io.frayground.agent.input.DecisionInputReader;
import io.frayground.agent.runtime.MockAgentRuntime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.PropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@SpringJUnitConfig(BlackjackSystemSettlementTest.Config.class)
class BlackjackSystemSettlementTest {
  @Autowired DecisionExecutionService lifecycle;
  @Autowired ActionSubmitter submitter;
  @Autowired RoomStore rooms;
  @Autowired MatchStore matches;
  @Autowired TurnStore turns;
  @Autowired SnapshotStore snapshots;
  @Autowired GameEventStore events;
  @Autowired SettlementStore settlements;
  @Autowired PlatformTransactionManager gameTransactionManager;
  @Autowired Clock clock;
  @Autowired GameRegistry registry;
  @Autowired AtomicReference<ShoeShuffler> shuffler;

  @Test void dealerNaturalFinishesDuringPrepareWithoutADecision() {
    UUID roomId = open(List.of(card("10"), card("A"), card("9"), card("K")));
    lifecycle.prepare(roomId);
    var match = matches.find(rooms.find(roomId).orElseThrow().activeMatchId()).orElseThrow();
    assertEquals(MatchStatus.FINISHED, match.status());
    assertEquals(1, match.stateVersion());
    assertNull(match.currentTurnId());
    assertEquals(GameOutcome.DRAW, match.result().status());
    assertEquals(ExecutionStatus.PAUSED, rooms.find(roomId).orElseThrow().executionStatus());
    assertTrue(events.readAfter(match.matchId(), 0, 100).stream().allMatch(event -> event.decisionId() == null));
    assertTrue(events.readAfter(match.matchId(), 0, 100).stream().anyMatch(event -> event.type().equals("ShoeShuffled")));
    assertThrows(IllegalStateException.class, () -> lifecycle.start(roomId));
  }

  @Test void playerActionAndDealerSettlementUseSeparateVersions() {
    UUID player = UUID.randomUUID();
    UUID roomId = open(List.of(new Participant(player, player, "P1")),
        List.of(card("10"), card("9"), card("6"), card("7"), card("5")));
    lifecycle.prepare(roomId);
    var started = lifecycle.start(roomId);
    assertEquals(1, started.stateVersion());
    submitter.submit(new ActionEnvelope("1.0", started.matchId(), player, started.turnId(), started.decisionId(),
        UUID.randomUUID(), 1, Map.of("type", "stand"))).toCompletableFuture().join();
    var match = matches.find(started.matchId()).orElseThrow();
    assertEquals(MatchStatus.FINISHED, match.status());
    assertEquals(3, match.stateVersion());
    assertEquals(GameOutcome.DRAW, match.result().status());
    assertEquals(-1.0, match.result().standings().get(0).score());
    assertTrue(settlements.findByTurnId(started.turnId()).isPresent());
    assertEquals(2, settlements.findByTurnId(started.turnId()).orElseThrow().toVersion());
    var recorded = events.readAfter(match.matchId(), 0, 100);
    assertTrue(recorded.stream().anyMatch(event -> event.decisionId() == null && event.stateVersion() == 1));
    assertTrue(recorded.stream().anyMatch(event -> started.decisionId().equals(event.decisionId()) && event.stateVersion() == 2));
    assertTrue(recorded.stream().anyMatch(event -> event.decisionId() == null && event.type().equals("DealerHit") && event.stateVersion() == 3));
    assertEquals(3, snapshots.find(match.matchId(), 3).orElseThrow().stateVersion());
  }

  private UUID open(List<Card> prefix) {
    return open(List.of(new Participant(UUID.randomUUID(), UUID.randomUUID(), "P1")), prefix);
  }

  private UUID open(List<Participant> participants, List<Card> prefix) {
    shuffler.set(() -> new ShoeShuffle(9, shoe(prefix)));
    var initial = new RegisteredMatchFactory(registry, JsonMapper.builder().build(), clock, UUID::randomUUID)
        .create(UUID.randomUUID(), BlackjackDefinition.KEY, participants);
    UUID roomId = initial.match().roomId();
    Room room = new Room(roomId, 0, 0, initial.match().matchId(), ExecutionMode.STEP, ExecutionStatus.RUNNING, RoomHealth.OK, null);
    Match ready = initial.match();
    Match active = new Match(ready.matchId(), ready.roomId(), ready.gameKey(), MatchStatus.ACTIVE, ready.stateVersion(),
        ready.gameEventSeq(), ready.currentTurnId(), ready.participants(), ready.result());
    new TransactionTemplate(gameTransactionManager).executeWithoutResult(tx -> {
      rooms.insert(room);
      matches.insert(active);
      snapshots.append(initial.initialSnapshot());
    });
    return roomId;
  }

  private static Card card(String rank) {
    return new Card(0, "CLUBS", rank);
  }

  private static List<Card> shoe(List<Card> prefix) {
    List<Card> cards = new ArrayList<>(BlackjackRules.standardShoe());
    for (int i = 0; i < prefix.size(); i++) {
      int found = -1;
      for (int j = i; j < cards.size(); j++) {
        if (cards.get(j).equals(prefix.get(i))) {
          found = j;
          break;
        }
      }
      Collections.swap(cards, i, found);
    }
    return List.copyOf(cards);
  }

  @Configuration
  @Import({MemoryStorageConfiguration.class, DecisionExecutionModule.class})
  @PropertySource("classpath:memory-test.properties")
  static class Config {
    @Bean PlatformTransactionManager gameTransactionManager(InMemoryStorage storage) {
      return new InMemoryTransactionManager(storage);
    }
    @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC); }
    @Bean DecisionInputReader reader() { return new DecisionInputReader(); }
    @Bean AgentRuntime runtime(DecisionInputReader reader, Clock clock) { return new MockAgentRuntime(reader, clock); }
    @Bean Supplier<UUID> ids() { return UUID::randomUUID; }
    @Bean JsonMapper json() { return JsonMapper.builder().build(); }
    @Bean AtomicReference<ShoeShuffler> shuffler() {
      return new AtomicReference<>(() -> new ShoeShuffle(1, BlackjackRules.standardShoe()));
    }
    @Bean BlackjackDefinition blackjackDefinition(AtomicReference<ShoeShuffler> shuffler) {
      return new BlackjackDefinition(() -> shuffler.get().shuffle());
    }
    @Bean GameRegistry registry(BlackjackDefinition definition) {
      return new DefaultGameRegistry(List.of(new GameRegistration<>(definition, new BlackjackCodec(),
          new BlackjackActionSchemaProvider())));
    }
    @Bean DecisionGameMetadata metadata() { return new BlackjackDecisionMetadata(); }
    @Bean SystemPhaseHandler blackjackSystemPhaseHandler(BlackjackDefinition definition) {
      return new BlackjackSystemPhaseHandler(definition);
    }
    @Bean AgentBindingsReader bindings(Clock clock) {
      return new AgentBindingsReader() {
        public Optional<HostedBindings> current(UUID agentId) {
          return Optional.of(new HostedBindings(new PromptVersion(agentId, agentId, 1, 1, "prompt", clock.instant()),
              new AgentConfiguration(agentId, agentId, 1, RuntimeType.MOCK,
                  new ModelConfiguration("mock", "mock", false, 0, 100, 10000, null, "JSON", false),
                  new MemoryPolicy(MemoryMode.STATELESS), clock.instant())));
        }
        public Optional<HostedBindings> versions(UUID agentId, UUID prompt, UUID config) {
          return current(agentId);
        }
      };
    }
  }
}
