package io.frayground.application;

import io.frayground.agent.AgentRunner;
import io.frayground.application.port.*;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

/** Explicitly imported scheduling module; callers supply storage and recovery. */
@Import(DecisionExecutionModule.class)
public class TurnSchedulingModule {
  @Bean RoomCommandService roomCommandService(RoomStore rooms,MatchStore matches,TurnStore turns,DecisionStore decisions,
      SnapshotStore snapshots,CommandReceiptStore receipts,RoomEventRepository events,MatchFactory factory,
      DecisionExecutionService lifecycle,Clock clock,Supplier<UUID> ids) {
    return new RoomCommandService(rooms,matches,turns,decisions,snapshots,receipts,events,factory,lifecycle,clock,ids);
  }
  @Bean RoomTickService roomTickService(RoomStore rooms,MatchStore matches,DecisionStore decisions,DecisionExecutionService lifecycle,RoomEventRepository events,Clock clock) {
    return new RoomTickService(rooms,matches,decisions,lifecycle,events,clock);
  }
  @Bean(name="roomControlExecutor",destroyMethod="") @ConditionalOnMissingBean(name="roomControlExecutor")
  ExecutorService roomControlExecutor() { return Executors.newCachedThreadPool(); }
  @Bean(name="decisionRunnerExecutor",destroyMethod="") @ConditionalOnMissingBean(name="decisionRunnerExecutor")
  ExecutorService decisionRunnerExecutor() { return Executors.newCachedThreadPool(); }
  @Bean(name="decisionDeadlineExecutor",destroyMethod="") @ConditionalOnMissingBean(name="decisionDeadlineExecutor")
  ScheduledExecutorService decisionDeadlineExecutor() { return Executors.newSingleThreadScheduledExecutor(); }
  @Bean(destroyMethod="close") DefaultTurnScheduler turnScheduler(RoomCommandService commands,RoomTickService ticks,
      DecisionExecutionService lifecycle,AgentRunner runner,RecoveryService recovery,RoomStore rooms,MatchStore matches,
      DecisionStore decisions,Clock clock,@Qualifier("roomControlExecutor") ExecutorService control,
      @Qualifier("decisionRunnerExecutor") ExecutorService workers,@Qualifier("decisionDeadlineExecutor") ScheduledExecutorService deadlines) {
    return new DefaultTurnScheduler(commands,ticks,new RoomExecutionLanes(control),lifecycle,runner,recovery,
        rooms,matches,decisions,workers,deadlines,clock);
  }
}
