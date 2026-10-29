package io.frayground.application;

import io.frayground.adapter.game.*;
import io.frayground.adapter.memory.InMemoryPendingDecisionOutputStore;
import io.frayground.agent.*;
import io.frayground.agent.input.DecisionInputReader;
import io.frayground.application.port.*;
import io.frayground.engine.*;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import tools.jackson.databind.json.JsonMapper;

/** Explicitly imported module; callers provide bindings, clock, IDs, runtime and registrations. */
@EnableTransactionManagement(proxyTargetClass=true)
public class DecisionExecutionModule {
  @Bean GameEngine decisionGameEngine() { return new DefaultGameEngine(); }
  @Bean ActionEnvelopeCanonicalizer actionEnvelopeCanonicalizer() { return new ActionEnvelopeCanonicalizer(); }
  @Bean PendingDecisionOutputStore pendingDecisionOutputStore() { return new InMemoryPendingDecisionOutputStore(); }
  @Bean DecisionGameAccess decisionGameAccess(GameRegistry registry,List<DecisionGameMetadata> metadata,GameEngine engine,JsonMapper json) {
    return new RegisteredDecisionGameAccess(registry,metadata,engine,json);
  }
  @Bean DecisionExecutionService decisionExecutionService(RoomStore rooms,MatchStore matches,TurnStore turns,
      DecisionStore decisions,DecisionRecordStore records,SnapshotStore snapshots,DecisionInputSnapshotStore inputs,DecisionExecutionClaimStore claims,PendingDecisionOutputStore pending,
      RoomEventRepository events,AgentBindingsReader bindings,DecisionGameAccess games,Clock clock,Supplier<UUID> ids,JsonMapper json) {
    return new DecisionExecutionService(rooms,matches,turns,decisions,records,snapshots,inputs,claims,pending,events,bindings,games,clock,ids,json);
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
