package io.frayground.config;

import io.frayground.adapter.agent.FixedLaboratoryBindings;
import io.frayground.agent.HostedBindings;
import io.frayground.adapter.memory.InMemoryDecisionHistoryReader;
import io.frayground.adapter.memory.InMemoryStorage;
import io.frayground.application.LaboratoryRoomInitializer;
import io.frayground.application.MemoryPromptPublisher;
import io.frayground.application.TurnSchedulingModule;
import io.frayground.application.query.LaboratoryQueryService;
import io.frayground.adapter.game.GameRegistry;
import io.frayground.adapter.game.connectfour.ConnectFourDecisionMetadata;
import io.frayground.web.*;
import tools.jackson.databind.json.JsonMapper;
import io.frayground.application.port.*;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

/** Assembly is available only in the explicit, in-memory laboratory profile. */
@Configuration(proxyBeanMethods = false)
@Profile("laboratory")
@ConditionalOnProperty(prefix = "frayground.storage", name = "mode", havingValue = "memory")
@EnableConfigurationProperties({LaboratoryProperties.class, LaboratoryAgentProperties.class})
@Import(TurnSchedulingModule.class)
public class LaboratoryApiConfiguration {
  @Bean Clock laboratoryClock() { return Clock.systemUTC(); }
  @Bean Supplier<UUID> laboratoryIds() { return UUID::randomUUID; }
  @Bean FixedLaboratoryBindings laboratoryBindings(LaboratoryProperties p, LaboratoryAgentProperties agents, Clock clock) {
    return new FixedLaboratoryBindings(Map.of(
        p.agentA(), binding(p.agentA(), agents.a(), clock),
        p.agentB(), binding(p.agentB(), agents.b(), clock)),
        Map.of(p.agentA(), "Agent A", p.agentB(), "Agent B"));
  }
  private static HostedBindings binding(UUID id, LaboratoryAgentProperties.Agent settings, Clock clock) {
    var createdAt = clock.instant();
    var promptId = UUID.nameUUIDFromBytes((id + ":prompt:1").getBytes(StandardCharsets.UTF_8));
    var configId = UUID.nameUUIDFromBytes((id + ":config:1").getBytes(StandardCharsets.UTF_8));
    var prompt = new io.frayground.domain.strategy.PromptVersion(promptId, id, 1, 1, settings.prompt(), createdAt);
    var credentialRef = settings.runtime() == io.frayground.domain.strategy.RuntimeType.LLM
        ? "system:" + settings.modelId() : null;
    var model = new io.frayground.domain.strategy.ModelConfiguration(settings.provider(), settings.modelId(),
        settings.thinking(), settings.temperature(), settings.maxOutputTokens(), settings.decisionTimeoutMs(),
        credentialRef, settings.outputStrategy(), settings.allowBeta());
    var config = new io.frayground.domain.strategy.AgentConfiguration(configId, id, 1, settings.runtime(), model,
        new io.frayground.domain.strategy.MemoryPolicy(io.frayground.domain.strategy.MemoryMode.STATELESS), createdAt);
    return new HostedBindings(prompt, config);
  }
  // A new in-memory process has no old data to recover.
  @Bean RecoveryService laboratoryRecovery() { return () -> {}; }
  @Bean ConnectFourDecisionMetadata laboratoryConnectFourMetadata() { return new ConnectFourDecisionMetadata(); }
  @Bean BoardProjector laboratoryProjector(GameRegistry registry,JsonMapper json) {
    return new ConnectFourViewProjector(registry,json);
  }
  @Bean LaboratoryViewMapper laboratoryViewMapper(BoardProjector projector, JsonMapper json) { return new LaboratoryViewMapper(projector, json); }
  @Bean MemoryPromptPublisher memoryPromptPublisher(RoomStore rooms, MatchStore matches, CommandReceiptStore receipts,
      RoomEventRepository events, FixedLaboratoryBindings prompts, Clock clock, Supplier<UUID> ids) {
    return new MemoryPromptPublisher(rooms, matches, receipts, events, prompts, clock, ids);
  }
  @Bean LaboratoryRequestDecoder laboratoryRequestDecoder() { return new LaboratoryRequestDecoder(); }
  @Bean RoomEventStreamService roomEventStreamService(LaboratoryQueryService queries) { return new RoomEventStreamService(queries); }
  @Bean DecisionHistoryReader laboratoryDecisionHistoryReader(InMemoryStorage storage) {
    return new InMemoryDecisionHistoryReader(storage);
  }
  @Bean LaboratoryQueryService laboratoryQueryService(LaboratoryProperties p, RoomStore rooms,
      MatchStore matches, TurnStore turns, DecisionStore decisions, DecisionRecordStore records,
      ActionReceiptStore receipts, SnapshotStore snapshots, GameEventStore gameEvents,
      RoomEventRepository roomEvents, DecisionHistoryReader history, DecisionGameAccess games) {
    return new LaboratoryQueryService(p.roomId(), rooms, matches, turns, decisions, records,
        receipts, snapshots, gameEvents, roomEvents, history, games);
  }
  @Bean LaboratoryRoomInitializer laboratoryRoomInitializer(RoomStore rooms, MatchStore matches,
      TurnStore turns, SnapshotStore snapshots, RoomEventRepository events, MatchFactory factory,
      Clock clock, Supplier<UUID> ids, LaboratoryProperties p) {
    return new LaboratoryRoomInitializer(rooms, matches, turns, snapshots, events, factory,
        clock, ids, p.roomId(), p.agentA(), p.agentB());
  }
}
