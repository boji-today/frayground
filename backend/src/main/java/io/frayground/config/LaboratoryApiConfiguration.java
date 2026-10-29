package io.frayground.config;

import io.frayground.adapter.agent.FixedLaboratoryBindings;
import io.frayground.adapter.memory.InMemoryDecisionHistoryReader;
import io.frayground.adapter.memory.InMemoryStorage;
import io.frayground.application.LaboratoryRoomInitializer;
import io.frayground.application.TurnSchedulingModule;
import io.frayground.application.query.LaboratoryQueryService;
import io.frayground.adapter.game.GameRegistry;
import io.frayground.adapter.game.connectfour.ConnectFourDecisionMetadata;
import io.frayground.web.*;
import tools.jackson.databind.json.JsonMapper;
import io.frayground.application.port.*;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

/** Assembly is available only in the explicit, in-memory laboratory profile. */
@Configuration(proxyBeanMethods = false)
@Profile("laboratory")
@ConditionalOnProperty(prefix = "frayground.storage", name = "mode", havingValue = "memory")
@EnableConfigurationProperties(LaboratoryProperties.class)
@Import(TurnSchedulingModule.class)
public class LaboratoryApiConfiguration {
  @Bean Clock laboratoryClock() { return Clock.systemUTC(); }
  @Bean Supplier<UUID> laboratoryIds() { return UUID::randomUUID; }
  @Bean FixedLaboratoryBindings laboratoryBindings(LaboratoryProperties p, Clock clock) {
    return new FixedLaboratoryBindings(p.agentA(), p.agentB(), clock.instant());
  }
  // A new in-memory process has no old data to recover.
  @Bean RecoveryService laboratoryRecovery() { return () -> {}; }
  @Bean ConnectFourDecisionMetadata laboratoryConnectFourMetadata() { return new ConnectFourDecisionMetadata(); }
  @Bean ConnectFourViewProjector laboratoryProjector(GameRegistry registry,JsonMapper json) {
    return new ConnectFourViewProjector(registry,json);
  }
  @Bean LaboratoryViewMapper laboratoryViewMapper(ConnectFourViewProjector projector) { return new LaboratoryViewMapper(projector); }
  @Bean LaboratoryRequestDecoder laboratoryRequestDecoder() { return new LaboratoryRequestDecoder(); }
  @Bean RoomEventStreamService roomEventStreamService(LaboratoryQueryService queries) { return new RoomEventStreamService(queries); }
  @Bean DecisionHistoryReader laboratoryDecisionHistoryReader(InMemoryStorage storage) {
    return new InMemoryDecisionHistoryReader(storage);
  }
  @Bean LaboratoryQueryService laboratoryQueryService(LaboratoryProperties p, RoomStore rooms,
      MatchStore matches, TurnStore turns, DecisionStore decisions, DecisionRecordStore records,
      ActionReceiptStore receipts, SnapshotStore snapshots, GameEventStore gameEvents,
      RoomEventRepository roomEvents, DecisionHistoryReader history) {
    return new LaboratoryQueryService(p.roomId(), rooms, matches, turns, decisions, records,
        receipts, snapshots, gameEvents, roomEvents, history);
  }
  @Bean LaboratoryRoomInitializer laboratoryRoomInitializer(RoomStore rooms, MatchStore matches,
      TurnStore turns, SnapshotStore snapshots, RoomEventRepository events, MatchFactory factory,
      Clock clock, Supplier<UUID> ids, LaboratoryProperties p) {
    return new LaboratoryRoomInitializer(rooms, matches, turns, snapshots, events, factory,
        clock, ids, p.roomId(), p.agentA(), p.agentB());
  }
}
