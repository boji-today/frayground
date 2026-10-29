package io.frayground.application;

import io.frayground.agent.*;
import io.frayground.application.port.*;
import io.frayground.domain.action.*;
import io.frayground.domain.match.*;
import io.frayground.engine.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.transaction.support.*;

/** Coordinates room commands and asynchronous decisions. */
public class DefaultTurnScheduler implements TurnScheduler, AutoCloseable {
  private static final long DEADLINE_RETRY_DELAY_MS=250;
  private final RoomCommandService commands; private final RoomTickService ticks; private final RoomExecutionLanes lanes;
  private final DecisionExecutionService lifecycle; private final AgentRunner runner; private final RecoveryService recovery;
  private final RoomStore rooms; private final MatchStore matches; private final DecisionStore decisions;
  private final ExecutorService workers; private final ScheduledExecutorService deadlines; private final Clock clock;
  private final Map<UUID,Handle> handles=new ConcurrentHashMap<>();
  private final Set<TickKey> pendingTicks=ConcurrentHashMap.newKeySet();
  private final Set<UUID> pendingCleanup=ConcurrentHashMap.newKeySet();
  private record TickKey(UUID roomId,UUID matchId) {}
  private record Handle(StartedDecision started,AtomicBoolean cancelled) {
    UUID matchId() { return started.matchId(); }
    UUID decisionId() { return started.decisionId(); }
  }
  public DefaultTurnScheduler(RoomCommandService commands,RoomTickService ticks,RoomExecutionLanes lanes,
      DecisionExecutionService lifecycle,AgentRunner runner,RecoveryService recovery,RoomStore rooms,MatchStore matches,
      DecisionStore decisions,ExecutorService workers,ScheduledExecutorService deadlines,Clock clock) {
    this.commands=commands; this.ticks=ticks; this.lanes=lanes; this.lifecycle=lifecycle; this.runner=runner; this.recovery=recovery;
    this.rooms=rooms; this.matches=matches; this.decisions=decisions; this.workers=workers; this.deadlines=deadlines; this.clock=clock;
  }
  @Override public CommandResult handleCommand(UUID roomId,ControlCommand command) {
    var future=new CompletableFuture<CommandResult>();
    lanes.submit(roomId,()-> { try {
      var execution=commands.execute(roomId,command);
      afterCommit(()-> {
        if (execution.cancelDecision()!=null) cancel(roomId,execution.cancelDecision());
        if (execution.tickMatch()!=null) scheduleTick(roomId,execution.tickMatch(),"command");
        if (execution.resumeDecision()!=null) resume(roomId,execution.result().matchId(),execution.resumeDecision());
      });
      future.complete(execution.result());
    } catch (Throwable e) { future.completeExceptionally(e); } });
    return future.join();
  }
  @Override public void scheduleTick(UUID roomId,UUID matchId,String reason) {
    afterCommit(()-> {
      TickKey key=new TickKey(roomId,matchId);
      if (!pendingTicks.add(key)) return;
      try { lanes.submit(roomId,()-> { try { start(roomId,matchId); } finally { pendingTicks.remove(key); } }); }
      catch (RuntimeException e) { pendingTicks.remove(key); throw e; }
    });
  }
  private void start(UUID roomId,UUID matchId) {
    Room before=rooms.find(roomId).orElse(null); Match beforeMatch=matches.find(matchId).orElse(null);
    Optional<StartedDecision> started;
    try { started=ticks.tick(roomId,matchId); }
    catch (RuntimeException failure) {
      RoomTickService.TickFailure guarded=failure instanceof RoomTickService.TickFailure tf ? tf
          : before!=null && beforeMatch!=null ? new RoomTickService.TickFailure(before,beforeMatch,failure) : null;
      try { if (guarded!=null) ticks.pauseFailedTick(guarded); }
      catch (RuntimeException e) { System.getLogger(getClass().getName()).log(System.Logger.Level.ERROR,"Failed to pause room after tick: "+roomId+" match="+matchId,e); }
      System.getLogger(getClass().getName()).log(System.Logger.Level.ERROR,"Tick failed: room="+roomId+" match="+matchId,failure);
      return;
    }
    if (started.isEmpty()) return;
    StartedDecision d=started.get(); Handle h=new Handle(d,new AtomicBoolean()); handles.put(roomId,h);
    deadline(roomId,d,h);
    execute(roomId,d,h,false);
  }
  private void resume(UUID roomId,UUID matchId,UUID decisionId) {
    Handle h=handles.get(roomId);
    if (h==null || !h.matchId().equals(matchId) || !h.decisionId().equals(decisionId) || h.cancelled().get()) return;
    execute(roomId,h.started(),h,true);
  }
  private void execute(UUID roomId,StartedDecision d,Handle h,boolean pendingOnly) {
    workers.execute(()-> {
      try {
        if (pendingOnly && !lifecycle.pendingOutputs().contains(d.decisionId())) return;
        runner.run(lifecycle.runtimeInput(d.decisionId()),h.cancelled()::get).whenComplete((value,error)->
            dispatchCompletion(roomId,d,h,error));
      } catch (Throwable e) { dispatchCompletion(roomId,d,h,e); }
    });
  }
  private void dispatchCompletion(UUID roomId,StartedDecision d,Handle h,Throwable error) {
    try { lanes.submit(roomId,()->completed(roomId,d,h,error)); }
    catch (RejectedExecutionException rejected) {
      log(d,rejected);
      if (!deadlines.isShutdown()) {
        try { deadlines.schedule(()->dispatchCompletion(roomId,d,h,error),DEADLINE_RETRY_DELAY_MS,TimeUnit.MILLISECONDS); }
        catch (RejectedExecutionException closed) { if (!deadlines.isShutdown()) log(d,closed); }
      }
    }
  }
  private void deadline(UUID roomId,StartedDecision d,Handle h) {
    long delay=Math.max(0,Duration.between(clock.instant(),d.deadline()).toMillis());
    scheduleDeadline(roomId,d,h,delay);
  }
  private void scheduleDeadline(UUID roomId,StartedDecision d,Handle h,long delay) {
    deadlines.schedule(()-> {
      try {
        lanes.submit(roomId,()-> {
          if (handles.get(roomId)!=h) return;
          if (clock.instant().isBefore(d.deadline())) { deadline(roomId,d,h); return; }
          try { if (ticks.finishIfCurrent(d,true)) { cancel(roomId,d.decisionId()); handles.remove(roomId,h); } }
          catch (RuntimeException e) { log(d,e); retryDeadline(roomId,d,h); }
        });
      } catch (RejectedExecutionException e) { log(d,e); retryDeadline(roomId,d,h); }
    },delay,TimeUnit.MILLISECONDS);
  }
  private void retryDeadline(UUID roomId,StartedDecision d,Handle h) {
    if (handles.get(roomId)!=h || h.cancelled().get() || deadlines.isShutdown()) return;
    try { scheduleDeadline(roomId,d,h,DEADLINE_RETRY_DELAY_MS); }
    catch (RejectedExecutionException e) { if (!deadlines.isShutdown()) log(d,e); }
  }
  private void completed(UUID roomId,StartedDecision d,Handle h,Throwable error) {
    boolean current=handles.get(roomId)==h;
    Decision observed=decisions.find(d.decisionId()).orElse(null);
    if (observed!=null && terminal(observed)) {
      handles.remove(roomId,h); cleanupTerminalOutput(roomId,d.decisionId());
      Room room=rooms.find(roomId).orElse(null);
      if (current && error==null && room!=null && Objects.equals(room.activeMatchId(),d.matchId()) && room.health()==RoomHealth.OK && room.executionStatus()==ExecutionStatus.RUNNING)
        scheduleTick(roomId,d.matchId(),"completed");
      return;
    }
    if (!current) return;
    if (error!=null) {
      if (lifecycle.pendingOutputs().contains(d.decisionId())) {
        try { ticks.pausePending(d); } catch (RuntimeException e) { log(d,e); }
        log(d,error); return;
      }
      try { if (ticks.finishIfCurrent(d,false)) handles.remove(roomId,h); } catch (RuntimeException e) { log(d,e); }
      return;
    }
    Decision decision=decisions.find(d.decisionId()).orElse(null);
    if (decision!=null && (decision.status()==DecisionStatus.PENDING || decision.status()==DecisionStatus.RUNNING)) return;
    handles.remove(roomId,h);
    Room room=rooms.find(roomId).orElse(null);
    if (room!=null && Objects.equals(room.activeMatchId(),d.matchId()) && room.health()==RoomHealth.OK && room.executionStatus()==ExecutionStatus.RUNNING)
      scheduleTick(roomId,d.matchId(),"completed");
  }
  private void cancel(UUID roomId,UUID decisionId) {
    Handle h=handles.get(roomId);
    if (h!=null && h.decisionId().equals(decisionId)) { h.cancelled().set(true); handles.remove(roomId,h); }
    cleanupTerminalOutput(roomId,decisionId);
  }
  private static boolean terminal(Decision decision) {
    return decision.status()!=DecisionStatus.RUNNING && decision.status()!=DecisionStatus.PENDING;
  }
  private void cleanupTerminalOutput(UUID roomId,UUID decisionId) {
    Decision decision=decisions.find(decisionId).orElse(null);
    if (decision==null || !terminal(decision)) return;
    var pending=lifecycle.pendingOutputs();
    if (pending.acquire(decisionId).isPresent()) {
      // Acquiring belongs to this cleanup attempt; only terminal decisions may be discarded.
      boolean discard=false;
      try { Decision current=decisions.find(decisionId).orElse(null); discard=current!=null && terminal(current); }
      finally { pending.release(decisionId,discard); }
    } else if (pending.contains(decisionId)) scheduleCleanup(roomId,decisionId);
  }
  private void scheduleCleanup(UUID roomId,UUID decisionId) {
    if (deadlines.isShutdown() || !pendingCleanup.add(decisionId)) return;
    try {
      deadlines.schedule(()-> {
        try {
          lanes.submit(roomId,()-> { pendingCleanup.remove(decisionId); cleanupTerminalOutput(roomId,decisionId); });
        } catch (RejectedExecutionException rejected) {
          pendingCleanup.remove(decisionId);
          scheduleCleanup(roomId,decisionId);
        }
      },DEADLINE_RETRY_DELAY_MS,TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException rejected) {
      pendingCleanup.remove(decisionId);
      if (!deadlines.isShutdown()) throw rejected;
    }
  }
  private void log(StartedDecision d,Throwable e) { System.getLogger(getClass().getName()).log(System.Logger.Level.ERROR,"Decision finalization failed: room="+d.roomId()+" match="+d.matchId()+" decision="+d.decisionId(),e); }
  private static void afterCommit(Runnable task) {
    if (TransactionSynchronizationManager.isActualTransactionActive() && TransactionSynchronizationManager.isSynchronizationActive())
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { @Override public void afterCommit() { task.run(); } });
    else task.run();
  }
  @Override public void recoverBeforeReady() { recovery.recoverBeforeReady(); }
  @Override public void close() { handles.values().forEach(h->h.cancelled().set(true)); deadlines.shutdownNow(); workers.shutdownNow(); lanes.close(); }
}
