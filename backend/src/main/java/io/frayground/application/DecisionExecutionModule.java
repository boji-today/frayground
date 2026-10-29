package io.frayground.application;

import io.frayground.adapter.game.*;
import io.frayground.adapter.memory.InMemoryPendingDecisionOutputStore;
import io.frayground.agent.*;
import io.frayground.agent.input.DecisionInputReader;
import io.frayground.agent.runtime.LlmAgentRuntime;
import io.frayground.agent.runtime.MockAgentRuntime;
import io.frayground.agent.runtime.RoutingAgentRuntime;
import io.frayground.agent.context.StatelessContextBuilder;
import io.frayground.application.port.*;
import io.frayground.engine.*;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.databind.json.JsonMapper;

/** Explicitly imported module; callers provide bindings, clock, IDs, runtime and registrations. */
@EnableTransactionManagement(proxyTargetClass=true)
public class DecisionExecutionModule {
  @Bean @ConditionalOnMissingBean(MockAgentRuntime.class)
  MockAgentRuntime decisionMockRuntime(DecisionInputReader reader,Clock clock) { return new MockAgentRuntime(reader,clock); }
  @Bean
  LlmAgentRuntime decisionLlmRuntime(org.springframework.beans.factory.ObjectProvider<AgentContextBuilder> builders,DecisionInputReader reader,org.springframework.beans.factory.ObjectProvider<ModelAdapter> adapters,DecisionModelAudit audit,Clock clock) {
    AgentContextBuilder builder=builders.getIfAvailable(()->new StatelessContextBuilder(reader,32_768));
    return new LlmAgentRuntime(builder,reader,adapters.getIfAvailable(DecisionExecutionModule::unavailableModelAdapter),audit,clock);
  }
  @Bean @org.springframework.context.annotation.Primary AgentRuntime decisionRuntimeRouter(MockAgentRuntime mock,LlmAgentRuntime llm) {
    return new RoutingAgentRuntime(mock,llm);
  }
  private static ModelAdapter unavailableModelAdapter() {
    return new ModelAdapter() {
      public ModelCapabilities capabilities(io.frayground.domain.strategy.ModelConfiguration c) { return new ModelCapabilities(false,false,false,false,false,Set.of()); }
      public java.util.concurrent.CompletionStage<ModelInvocationResult> invoke(ModelRequest r,CancellationSignal c) {
        return java.util.concurrent.CompletableFuture.completedFuture(new ModelInvocationResult(null,null,null,List.of(),null,null,null,new RuntimeError("MODEL_ADAPTER_UNAVAILABLE","MODEL_ADAPTER_UNAVAILABLE")));
      }
    };
  }
  @Bean GameEngine decisionGameEngine() { return new DefaultGameEngine(); }
  @Bean ActionEnvelopeCanonicalizer actionEnvelopeCanonicalizer() { return new ActionEnvelopeCanonicalizer(); }
  @Bean PendingDecisionOutputStore pendingDecisionOutputStore() { return new InMemoryPendingDecisionOutputStore(); }
  @Bean DecisionModelAudit decisionModelAudit(DecisionRecordStore records,DecisionStore decisions,MatchStore matches,RoomStore rooms,JsonMapper json) {
    return new DecisionModelAuditService(records,decisions,matches,rooms,json);
  }
  @Bean DecisionGameAccess decisionGameAccess(GameRegistry registry,List<DecisionGameMetadata> metadata,
      List<SystemPhaseHandler> systemPhases,GameEngine engine,JsonMapper json) {
    return new RegisteredDecisionGameAccess(registry,metadata,systemPhases,engine,json);
  }
  @Bean DecisionExecutionService decisionExecutionService(RoomStore rooms,MatchStore matches,TurnStore turns,
      DecisionStore decisions,DecisionRecordStore records,SnapshotStore snapshots,GameEventStore gameEvents,DecisionInputSnapshotStore inputs,DecisionExecutionClaimStore claims,PendingDecisionOutputStore pending,
      RoomEventRepository events,AgentBindingsReader bindings,DecisionGameAccess games,Clock clock,Supplier<UUID> ids,JsonMapper json,DecisionModelAudit audit) {
    return new DecisionExecutionService(rooms,matches,turns,decisions,records,snapshots,gameEvents,inputs,claims,pending,events,bindings,games,clock,ids,json,audit);
  }
  @Bean TransactionalActionSubmitter transactionalActionSubmitter(RoomStore rooms,MatchStore matches,TurnStore turns,
      DecisionStore decisions,DecisionRecordStore records,SnapshotStore snapshots,GameEventStore gameEvents,
      SettlementStore settlements,ActionReceiptStore receipts,DecisionGameAccess games,DecisionExecutionService lifecycle,
      Clock clock,Supplier<UUID> ids,ActionEnvelopeCanonicalizer canonicalizer,JsonMapper json) {
    return new TransactionalActionSubmitter(rooms,matches,turns,decisions,records,snapshots,gameEvents,settlements,
        receipts,games,lifecycle,clock,ids,canonicalizer,json);
  }
  @Bean AgentRunner decisionAgentRunner(AgentRuntime runtime,DecisionExecutionService lifecycle,ActionSubmitter submitter,
      DecisionInputReader reader,JsonMapper json,Supplier<UUID> ids) {
    return new DefaultAgentRunner(runtime,lifecycle,submitter,reader,json,ids);
  }
}
