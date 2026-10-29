package io.frayground.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.frayground.application.port.*;
import io.frayground.domain.game.*;
import io.frayground.domain.match.*;
import io.frayground.engine.StartedDecision;
import io.frayground.agent.AgentRunner;
import io.frayground.domain.action.*;
import java.time.Instant;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class RoomTickServiceTest {
  @Test void prepareMayCreateFirstTurnBeforeStarting() {
    UUID roomId=UUID.randomUUID(),matchId=UUID.randomUUID(),turnId=UUID.randomUUID();
    RoomStore rooms=mock(RoomStore.class); MatchStore matches=mock(MatchStore.class); DecisionStore decisions=mock(DecisionStore.class);
    DecisionExecutionService lifecycle=mock(DecisionExecutionService.class);
    Room room=new Room(roomId,0,0,matchId,ExecutionMode.AUTO,ExecutionStatus.RUNNING,RoomHealth.OK,null);
    Match initial=new Match(matchId,roomId,new GameKey("example","1"),MatchStatus.ACTIVE,0,0,null,List.of(),null);
    Match prepared=new Match(matchId,roomId,initial.gameKey(),MatchStatus.ACTIVE,1,0,turnId,List.of(),null);
    StartedDecision started=new StartedDecision(roomId,matchId,UUID.randomUUID(),UUID.randomUUID(),turnId,UUID.randomUUID(),UUID.randomUUID(),1,1,"{}","{}",Instant.now());
    when(rooms.find(roomId)).thenReturn(Optional.of(room)); when(matches.find(matchId)).thenReturn(Optional.of(initial),Optional.of(prepared));
    when(lifecycle.start(roomId)).thenReturn(started);
    assertEquals(Optional.of(started),new RoomTickService(rooms,matches,decisions,lifecycle,mock(RoomEventRepository.class),Clock.systemUTC()).tick(roomId,matchId));
    verify(lifecycle).prepare(roomId); verify(lifecycle).start(roomId);
  }
  @Test void prepareMayFinishWithoutStartingDecision() {
    UUID roomId=UUID.randomUUID(),matchId=UUID.randomUUID();
    RoomStore rooms=mock(RoomStore.class); MatchStore matches=mock(MatchStore.class); DecisionStore decisions=mock(DecisionStore.class);
    DecisionExecutionService lifecycle=mock(DecisionExecutionService.class);
    Room room=new Room(roomId,0,0,matchId,ExecutionMode.AUTO,ExecutionStatus.RUNNING,RoomHealth.OK,null);
    Match initial=new Match(matchId,roomId,new GameKey("example","1"),MatchStatus.ACTIVE,0,0,null,List.of(),null);
    Match finished=new Match(matchId,roomId,initial.gameKey(),MatchStatus.FINISHED,1,0,null,List.of(),null);
    when(rooms.find(roomId)).thenReturn(Optional.of(room)); when(matches.find(matchId)).thenReturn(Optional.of(initial),Optional.of(finished));
    assertTrue(new RoomTickService(rooms,matches,decisions,lifecycle,mock(RoomEventRepository.class),Clock.systemUTC()).tick(roomId,matchId).isEmpty());
    verify(lifecycle).prepare(roomId); verify(lifecycle,never()).start(any());
  }
  @Test void staleTickFailureCannotPauseReplacementMatch() {
    UUID roomId=UUID.randomUUID(),oldId=UUID.randomUUID(),newId=UUID.randomUUID();
    RoomStore rooms=mock(RoomStore.class); MatchStore matches=mock(MatchStore.class); DecisionStore decisions=mock(DecisionStore.class);
    DecisionExecutionService lifecycle=mock(DecisionExecutionService.class); RoomEventRepository events=mock(RoomEventRepository.class);
    Room oldRoom=new Room(roomId,1,1,oldId,ExecutionMode.AUTO,ExecutionStatus.RUNNING,RoomHealth.OK,null);
    Room newRoom=new Room(roomId,2,2,newId,ExecutionMode.AUTO,ExecutionStatus.RUNNING,RoomHealth.OK,null);
    Match oldMatch=new Match(oldId,roomId,new GameKey("example","1"),MatchStatus.ACTIVE,0,0,null,List.of(),null);
    when(rooms.find(roomId)).thenReturn(Optional.of(newRoom)); when(matches.find(oldId)).thenReturn(Optional.of(oldMatch));
    new RoomTickService(rooms,matches,decisions,lifecycle,events,Clock.systemUTC())
        .pauseFailedTick(new RoomTickService.TickFailure(oldRoom,oldMatch,new IllegalStateException("failed")));
    verify(rooms,never()).replace(any(),any()); verify(events,never()).append(any());
  }
  @Test void shutdownCancelsOutstandingTerminalCleanup() throws Exception {
    UUID roomId=UUID.randomUUID(),matchId=UUID.randomUUID(),decisionId=UUID.randomUUID();
    RoomCommandService commands=mock(RoomCommandService.class); RoomTickService ticks=mock(RoomTickService.class);
    DecisionExecutionService lifecycle=mock(DecisionExecutionService.class); var pending=mock(PendingDecisionOutputStore.class);
    DecisionStore decisions=mock(DecisionStore.class); RoomStore rooms=mock(RoomStore.class); MatchStore matches=mock(MatchStore.class);
    var deadline=new TurnSchedulingIntegrationTest.ManualDeadlines(); var workers=Executors.newSingleThreadExecutor();
    var control=new TurnSchedulingIntegrationTest.RejectingControlExecutor(); var lanes=new RoomExecutionLanes(control);
    var scheduler=new DefaultTurnScheduler(commands,ticks,lanes,lifecycle,mock(AgentRunner.class),mock(RecoveryService.class),rooms,matches,decisions,workers,deadline,Clock.systemUTC());
    var command=new ControlCommand(UUID.randomUUID(),0,matchId,ControlCommandType.CANCEL_DECISION,null);
    var result=new CommandResult(command.commandId(),null,1,matchId,ExecutionMode.AUTO,ExecutionStatus.PAUSED);
    var decision=mock(Decision.class); when(decision.status()).thenReturn(DecisionStatus.CANCELLED);
    when(decisions.find(decisionId)).thenReturn(Optional.of(decision)); when(lifecycle.pendingOutputs()).thenReturn(pending);
    when(pending.acquire(decisionId)).thenReturn(Optional.empty()); when(pending.contains(decisionId)).thenReturn(true);
    when(commands.execute(roomId,command)).thenReturn(new RoomCommandService.CommandExecution(result,null,decisionId,null));
    try {
      assertEquals(result,scheduler.handleCommand(roomId,command)); control.awaitIdle(); control.armRejection();
      deadline.firePending(); assertTrue(control.rejection.await(5,TimeUnit.SECONDS)); assertEquals(1,deadline.scheduled.size());
    }
    finally { scheduler.close(); }
    assertTrue(deadline.isShutdown()); assertTrue(workers.isShutdown());
    assertTrue(deadline.scheduled.isEmpty());
  }
}
