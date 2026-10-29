package io.frayground.application;

import static org.junit.jupiter.api.Assertions.*;
import io.frayground.adapter.game.*;
import io.frayground.adapter.game.connectfour.*;
import io.frayground.adapter.memory.*;
import io.frayground.agent.*;
import io.frayground.agent.input.DecisionInputReader;
import io.frayground.agent.runtime.MockAgentRuntime;
import io.frayground.application.port.*;
import io.frayground.config.MemoryStorageConfiguration;
import io.frayground.domain.action.*;
import io.frayground.domain.game.*;
import io.frayground.domain.game.connectfour.*;
import io.frayground.domain.match.*;
import io.frayground.domain.strategy.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@SpringJUnitConfig(DecisionExecutionIntegrationTest.Config.class)
class DecisionExecutionIntegrationTest {
  @Autowired DecisionExecutionService lifecycle;
  @Autowired ActionSubmitter submitter;
  @Autowired AgentRunner runner;
  @Autowired RoomStore rooms; @Autowired MatchStore matches; @Autowired TurnStore turns;
  @Autowired DecisionStore decisions; @Autowired SnapshotStore snapshots; @Autowired DecisionRecordStore records;
  @Autowired SettlementStore settlements; @Autowired PlatformTransactionManager gameTransactionManager;
  @Autowired GameRegistry registry; @Autowired Clock clock; @Autowired JsonMapper json;
  @Autowired FaultGate faultGate;
  @Autowired ActionReceiptStore receipts;
  @Autowired RoomEventRepository roomEvents;
  @Autowired BindingSource bindingSource;

  @Test void mockRunsFixedInputAndCommitsOnce() {
    Fixture f=fixture(); var started=lifecycle.start(f.room.roomId());
    var input=lifecycle.runtimeInput(started.decisionId());
    var result=(DecisionOutcome.Submitted)runner.run(input,()->false).toCompletableFuture().join();
    assertEquals(SubmissionStatus.EXECUTED,result.submission().status());
    assertEquals(1,matches.find(f.match.matchId()).orElseThrow().stateVersion());
    assertEquals(DecisionStatus.COMPLETED,decisions.find(started.decisionId()).orElseThrow().status());
    assertEquals(TurnStatus.RESOLVED,turns.find(started.turnId()).orElseThrow().status());
    assertTrue(settlements.findByTurnId(started.turnId()).isPresent());
    assertNotNull(snapshots.find(f.match.matchId(),1).orElseThrow());
    assertNull(decisions.find(started.decisionId()).orElseThrow().requestJson());
    assertNull(records.find(started.decisionId()).orElseThrow().modelOutputJson());
    assertEquals(ExecutionStatus.PAUSED,rooms.find(f.room.roomId()).orElseThrow().executionStatus());
  }
  @Test void replayIsIdempotentAndIdentityConflictDoesNotChangeFacts() {
    Fixture f=fixture(); var s=lifecycle.start(f.room.roomId()); UUID action=UUID.randomUUID();
    var e=new ActionEnvelope("1.0",s.matchId(),s.participantId(),s.turnId(),s.decisionId(),action,0,Map.of("type","place_disc","column",0));
    var first=submitter.submit(e).toCompletableFuture().join();
    var replay=submitter.submit(new ActionEnvelope("1.0",s.matchId(),s.participantId(),s.turnId(),s.decisionId(),action,0,Map.of("column",0L,"type","place_disc"))).toCompletableFuture().join();
    assertEquals(first,replay);
    var changed=new ActionEnvelope("1.0",s.matchId(),s.participantId(),s.turnId(),s.decisionId(),action,0,Map.of("type","place_disc","column",1));
    assertEquals("ACTION_ID_CONFLICT",submitter.submit(changed).toCompletableFuture().join().errorCode());
    assertEquals(1,matches.find(s.matchId()).orElseThrow().stateVersion());
    assertEquals(first,records.find(s.decisionId()).orElseThrow().submissionResult());
  }
  @Test void invalidActionFailsWithoutAdvancingAndCanRetrySameTurn() {
    Fixture f=fixture(); var s=lifecycle.start(f.room.roomId());
    var e=new ActionEnvelope("1.0",s.matchId(),s.participantId(),s.turnId(),s.decisionId(),UUID.randomUUID(),0,Map.of("type","place_disc","column",99));
    assertEquals("INVALID_ACTION",submitter.submit(e).toCompletableFuture().join().errorCode());
    assertEquals(DecisionStatus.FAILED,decisions.find(s.decisionId()).orElseThrow().status());
    assertEquals(TurnStatus.OPEN,turns.find(s.turnId()).orElseThrow().status());
    assertEquals(0,matches.find(s.matchId()).orElseThrow().stateVersion());
    assertTrue(settlements.findByTurnId(s.turnId()).isEmpty());
    new TransactionTemplate(gameTransactionManager).executeWithoutResult(tx->{ Room old=rooms.find(f.room.roomId()).orElseThrow();
      assertTrue(rooms.replace(old,new Room(old.roomId(),old.revision(),old.eventSeq(),old.activeMatchId(),old.executionMode(),ExecutionStatus.RUNNING,old.health(),old.healthError()))); });
    var retry=lifecycle.start(f.room.roomId()); assertEquals(s.turnId(),retry.turnId()); assertNotEquals(s.decisionId(),retry.decisionId());
  }
  @Test void cancelClosesTurnAndLateFailureCannotDamageReplacement() {
    Fixture f=fixture(); var s=lifecycle.start(f.room.roomId()); lifecycle.cancel(f.room.roomId(),UUID.randomUUID());
    assertEquals(DecisionStatus.CANCELLED,decisions.find(s.decisionId()).orElseThrow().status());
    assertEquals(TurnStatus.CANCELLED,turns.find(s.turnId()).orElseThrow().status());
    new TransactionTemplate(gameTransactionManager).executeWithoutResult(tx->{ Room old=rooms.find(f.room.roomId()).orElseThrow();
      assertTrue(rooms.replace(old,new Room(old.roomId(),old.revision(),old.eventSeq(),old.activeMatchId(),old.executionMode(),ExecutionStatus.RUNNING,old.health(),old.healthError()))); });
    var retry=lifecycle.start(f.room.roomId()); lifecycle.runtimeFailed(s.decisionId(),"RUNTIME_ERROR");
    assertNotEquals(s.turnId(),retry.turnId()); assertEquals(DecisionStatus.RUNNING,decisions.find(retry.decisionId()).orElseThrow().status());
    assertEquals(0,matches.find(s.matchId()).orElseThrow().stateVersion());
  }
  @ParameterizedTest @ValueSource(strings={"cancel","expired"})
  void cancelRecordsAuthoritativeErrorWithoutLateCallback(String kind) {
    Fixture f=fixture(); var s=lifecycle.start(f.room.roomId()); var mutable=(MutableClock)clock;
    try {
      if (kind.equals("expired")) mutable.set(s.deadline());
      lifecycle.cancel(f.room.roomId(),UUID.randomUUID());
      String code=kind.equals("expired")?"DECISION_EXPIRED":"DECISION_CANCELLED";
      assertEquals(code,decisions.find(s.decisionId()).orElseThrow().errorCode());
      assertTrue(records.find(s.decisionId()).orElseThrow().errorJson().contains(code));
      assertEquals(kind.equals("expired")?TurnStatus.OPEN:TurnStatus.CANCELLED,turns.find(s.turnId()).orElseThrow().status());
      var before=records.find(s.decisionId()).orElseThrow(); lifecycle.cancel(f.room.roomId(),UUID.randomUUID());
      assertEquals(before,records.find(s.decisionId()).orElseThrow());
    } finally { mutable.set(MutableClock.INITIAL); }
  }
  @ParameterizedTest @ValueSource(strings={"record.replace","false:record.replace","roomEvent.append"})
  void cancellationAuditFailureRollsBack(String fault) {
    Fixture f=fixture(); var s=lifecycle.start(f.room.roomId()); var room=rooms.find(f.room.roomId()).orElseThrow(); var record=records.find(s.decisionId()).orElseThrow();
    if (fault.startsWith("false:")) faultGate.falseTarget=fault.substring(6); else faultGate.target=fault;
    try { assertThrows(RuntimeException.class,()->lifecycle.cancel(f.room.roomId(),UUID.randomUUID())); }
    finally { faultGate.target=null; faultGate.falseTarget=null; }
    assertEquals(room,rooms.find(f.room.roomId()).orElseThrow()); assertEquals(record,records.find(s.decisionId()).orElseThrow());
    assertEquals(DecisionStatus.RUNNING,decisions.find(s.decisionId()).orElseThrow().status());
    assertEquals(TurnStatus.OPEN,turns.find(s.turnId()).orElseThrow().status());
    lifecycle.cancel(f.room.roomId(),UUID.randomUUID());
    assertTrue(records.find(s.decisionId()).orElseThrow().errorJson().contains("DECISION_CANCELLED"));
  }
  @ParameterizedTest @ValueSource(strings={"ordinary","early-expiry","server-deadline","late-cancel","late-completed","recovery-deadline"})
  void runnerFailureReturnsAuthoritativeCode(String scenario) {
    Fixture f=fixture(); var s=lifecycle.start(f.room.roomId()); var input=lifecycle.runtimeInput(s.decisionId());
    var pending=new CompletableFuture<RuntimeOutcome>(); var calls=new AtomicInteger();
    AgentRuntime runtime=(i,c)->{calls.incrementAndGet();return pending;};
    var r=new DefaultAgentRunner(runtime,lifecycle,submitter,new DecisionInputReader(),json,UUID::randomUUID);
    var result=r.run(input,()->false); var mutable=(MutableClock)clock;
    try {
      if (scenario.equals("late-cancel")) lifecycle.cancel(f.room.roomId(),UUID.randomUUID());
      if (scenario.equals("late-completed")) {
        var e=new ActionEnvelope("1.0",s.matchId(),s.participantId(),s.turnId(),s.decisionId(),UUID.randomUUID(),0,Map.of("type","place_disc","column",0));
        assertEquals(SubmissionStatus.EXECUTED,submitter.submit(e).toCompletableFuture().join().status());
      }
      if (scenario.equals("server-deadline")) mutable.set(s.deadline());
      if (scenario.equals("recovery-deadline")) faultGate.target="decision.replace";
      String incoming=scenario.equals("early-expiry")?"DECISION_EXPIRED":"MODEL_UNAVAILABLE";
      pending.complete(new RuntimeOutcome.Failed(new RuntimeError(incoming,"runtime detail")));
      if (scenario.equals("recovery-deadline")) {
        var initialResult=result;
        assertThrows(CompletionException.class,()->initialResult.toCompletableFuture().join()); faultGate.target=null; mutable.set(s.deadline());
        result=r.run(input,()->false);
      }
      var returned=((DecisionOutcome.NotSubmitted)result.toCompletableFuture().join()).error().code();
      String expected=switch(scenario) { case "early-expiry" -> "RUNTIME_ERROR"; case "server-deadline","recovery-deadline" -> "DECISION_EXPIRED";
        case "late-cancel" -> "DECISION_CANCELLED"; case "late-completed" -> "DECISION_NOT_RUNNING"; default -> "MODEL_UNAVAILABLE"; };
      assertEquals(expected,returned); assertEquals(1,calls.get());
      Decision d=decisions.find(s.decisionId()).orElseThrow();
      if (d.status()!=DecisionStatus.COMPLETED) { assertEquals(expected,d.errorCode()); assertTrue(records.find(s.decisionId()).orElseThrow().errorJson().contains(expected)); }
      if (scenario.equals("early-expiry") || scenario.equals("server-deadline") || scenario.equals("recovery-deadline"))
        assertTrue(records.find(s.decisionId()).orElseThrow().diagnosticsJson().contains(incoming));
    } finally { faultGate.target=null; mutable.set(MutableClock.INITIAL); }
  }
  @Test void mismatchedRuntimeInputIsRejectedBeforeRuntime() {
    Fixture f=fixture(); var s=lifecycle.start(f.room.roomId()); var fixed=lifecycle.runtimeInput(s.decisionId());
    assertThrows(IllegalArgumentException.class,()->runner.run(new RuntimeInput(fixed.publicRequest(),Optional.empty()),()->false));
    assertEquals(DecisionStatus.RUNNING,decisions.find(s.decisionId()).orElseThrow().status());
  }
  @Test void threeSeatsCanUseNonstandardOrderRoundAndEventCounts() {
    UUID roomId=UUID.randomUUID(); var participants=List.of(new Participant(UUID.randomUUID(),UUID.randomUUID(),"one"),
        new Participant(UUID.randomUUID(),UUID.randomUUID(),"two"),new Participant(UUID.randomUUID(),UUID.randomUUID(),"three"));
    var initial=new RegisteredMatchFactory(registry,json,clock,UUID::randomUUID).create(roomId,ThreeSeatGameFixture.KEY,participants);
    var original=initial.match(); var match=new Match(original.matchId(),roomId,original.gameKey(),MatchStatus.ACTIVE,0,0,original.currentTurnId(),participants,original.result());
    var room=new Room(roomId,0,0,match.matchId(),ExecutionMode.AUTO,ExecutionStatus.RUNNING,RoomHealth.OK,null);
    new TransactionTemplate(gameTransactionManager).executeWithoutResult(tx->{ rooms.insert(room); matches.insert(match); turns.insert(match.matchId(),initial.firstTurn()); snapshots.append(initial.initialSnapshot()); });
    for (int i=0;i<3;i++) {
      var s=lifecycle.start(roomId);
      assertEquals(participants.get(i<2?2:0).participantId(),s.participantId());
      assertTrue(s.observationJson().contains("private:"));
      var outcome=(DecisionOutcome.Submitted)runner.run(lifecycle.runtimeInput(s.decisionId()),()->false).toCompletableFuture().join();
      assertEquals(SubmissionStatus.EXECUTED,outcome.submission().status());
      var current=matches.find(match.matchId()).orElseThrow();
      assertEquals(i+1,current.stateVersion()); assertEquals(i*(i+1)/2,current.gameEventSeq());
      if (i<2) assertEquals(10+(i+2)/3,turns.find(current.currentTurnId()).orElseThrow().roundNo());
      else { assertEquals(MatchStatus.FINISHED,current.status()); assertNull(current.currentTurnId()); }
    }
  }
  @Test void concurrentStartsProduceOnlyOneRunningDecision() throws Exception {
    Fixture f=fixture(); var ready=new CountDownLatch(2); var go=new CountDownLatch(1);
    ExecutorService pool=Executors.newFixedThreadPool(2);
    try {
      Callable<Boolean> task=()->{ ready.countDown(); go.await(); try { lifecycle.start(f.room.roomId()); return true; } catch (IllegalStateException e) { return false; } };
      var a=pool.submit(task); var b=pool.submit(task); assertTrue(ready.await(5,TimeUnit.SECONDS)); go.countDown();
      assertNotEquals(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS));
      assertEquals(1,decisions.findByTurn(f.match.currentTurnId()).size());
    } finally { pool.shutdownNow(); }
  }
  @Test void runtimeDoesNotHoldTransactionAndLateCompletionDoesNotSubmit() {
    Fixture f=fixture(); var s=lifecycle.start(f.room.roomId()); var future=new CompletableFuture<RuntimeOutcome>(); var active=new AtomicBoolean(true);
    AgentRuntime delayed=(input,cancel)->{ active.set(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()); return future; };
    AgentRunner delayedRunner=new DefaultAgentRunner(delayed,lifecycle,submitter,new DecisionInputReader(),json,UUID::randomUUID);
    var pending=delayedRunner.run(lifecycle.runtimeInput(s.decisionId()),()->false);
    assertFalse(active.get()); assertFalse(pending.toCompletableFuture().isDone());
    lifecycle.cancel(f.room.roomId(),UUID.randomUUID()); future.complete(new RuntimeOutcome.SelectedAction("c0"));
    assertInstanceOf(DecisionOutcome.NotSubmitted.class,pending.toCompletableFuture().join());
    assertEquals(DecisionStatus.CANCELLED,decisions.find(s.decisionId()).orElseThrow().status());
    assertTrue(settlements.findByTurnId(s.turnId()).isEmpty());
  }
  @Test void writeFailureRollsBackReceiptSettlementSnapshotAndDecisionThenSameEnvelopeRetries() {
    Fixture f=fixture(); var s=lifecycle.start(f.room.roomId());
    var e=new ActionEnvelope("1.0",s.matchId(),s.participantId(),s.turnId(),s.decisionId(),UUID.randomUUID(),0,Map.of("type","place_disc","column",0));
    var before=rooms.find(f.room.roomId()).orElseThrow(); faultGate.fail=true;
    try { assertThrows(IllegalStateException.class,()->submitter.submit(e)); }
    finally { faultGate.fail=false; }
    assertEquals(before,rooms.find(f.room.roomId()).orElseThrow());
    assertEquals(0,matches.find(s.matchId()).orElseThrow().stateVersion());
    assertEquals(DecisionStatus.RUNNING,decisions.find(s.decisionId()).orElseThrow().status());
    assertEquals(TurnStatus.OPEN,turns.find(s.turnId()).orElseThrow().status());
    assertTrue(snapshots.find(s.matchId(),1).isEmpty()); assertTrue(settlements.findByTurnId(s.turnId()).isEmpty());
    assertNull(records.find(s.decisionId()).orElseThrow().submissionResult());
    assertEquals(SubmissionStatus.EXECUTED,submitter.submit(e).toCompletableFuture().join().status());
  }
  @ParameterizedTest
  @ValueSource(strings={"receipt.saveFinal","settlement.save","snapshot.append","gameEvent.append","decision.replace",
      "record.replace","turn.replace","turn.insert","match.compareAndSetVersion","match.replace","room.replace","roomEvent.append"})
  void everySettlementWriteFailureRollsBack(String fault) {
    Fixture f=fixture(); var s=lifecycle.start(f.room.roomId()); var roomBefore=rooms.find(f.room.roomId()).orElseThrow();
    var e=new ActionEnvelope("1.0",s.matchId(),s.participantId(),s.turnId(),s.decisionId(),UUID.randomUUID(),0,Map.of("type","place_disc","column",0));
    faultGate.target=fault;
    try { assertThrows(IllegalStateException.class,()->submitter.submit(e)); }
    finally { faultGate.target=null; }
    assertEquals(roomBefore,rooms.find(f.room.roomId()).orElseThrow());
    assertEquals(f.match,matches.find(s.matchId()).orElseThrow());
    assertEquals(TurnStatus.OPEN,turns.find(s.turnId()).orElseThrow().status());
    assertEquals(DecisionStatus.RUNNING,decisions.find(s.decisionId()).orElseThrow().status());
    assertTrue(snapshots.find(s.matchId(),1).isEmpty()); assertTrue(receipts.findByActionId(e.actionId()).isEmpty());
    assertTrue(settlements.findByTurnId(s.turnId()).isEmpty()); assertNull(records.find(s.decisionId()).orElseThrow().submissionResult());
    assertEquals(SubmissionStatus.EXECUTED,submitter.submit(e).toCompletableFuture().join().status());
  }
  @Test void deadlineAtExactInstantExpiresAndEarlyExpireDoesNothing() {
    Fixture f=fixture(); var s=lifecycle.start(f.room.roomId()); lifecycle.expire(s.decisionId());
    assertEquals(DecisionStatus.RUNNING,decisions.find(s.decisionId()).orElseThrow().status());
    MutableClock mutable=(MutableClock)clock; mutable.set(s.deadline());
    try {
      var e=new ActionEnvelope("1.0",s.matchId(),s.participantId(),s.turnId(),s.decisionId(),UUID.randomUUID(),0,Map.of("type","place_disc","column",0));
      assertEquals("DECISION_EXPIRED",submitter.submit(e).toCompletableFuture().join().errorCode());
      assertEquals(DecisionStatus.EXPIRED,decisions.find(s.decisionId()).orElseThrow().status());
      assertEquals(0,matches.find(s.matchId()).orElseThrow().stateVersion());
      lifecycle.expire(s.decisionId()); assertEquals(DecisionStatus.EXPIRED,decisions.find(s.decisionId()).orElseThrow().status());
    } finally { mutable.set(MutableClock.INITIAL); }
  }
  @Test void concurrentSameEnvelopeHasOneSettlement() throws Exception {
    Fixture f=fixture(); var s=lifecycle.start(f.room.roomId());
    var e=new ActionEnvelope("1.0",s.matchId(),s.participantId(),s.turnId(),s.decisionId(),UUID.randomUUID(),0,Map.of("type","place_disc","column",0));
    var ready=new CountDownLatch(2); var go=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
    try {
      Callable<ActionSubmissionResult> task=()->{ ready.countDown(); go.await(); return submitter.submit(e).toCompletableFuture().join(); };
      var a=pool.submit(task); var b=pool.submit(task); assertTrue(ready.await(5,TimeUnit.SECONDS)); go.countDown();
      assertEquals(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS));
      var advanced=matches.find(s.matchId()).orElseThrow();
      assertEquals(1,advanced.stateVersion());
      var next=turns.find(advanced.currentTurnId()).orElseThrow();
      assertEquals(advanced.stateVersion(),next.baseStateVersion()); assertEquals(TurnStatus.OPEN,next.status());
      assertTrue(settlements.findByTurnId(s.turnId()).isPresent());
    } finally { pool.shutdownNow(); }
  }
  @Test void mockFinishesAFullConnectFourMatch() {
    Fixture f=fixture(); new TransactionTemplate(gameTransactionManager).executeWithoutResult(tx->{ Room old=rooms.find(f.room.roomId()).orElseThrow();
      assertTrue(rooms.replace(old,new Room(old.roomId(),old.revision(),old.eventSeq(),old.activeMatchId(),ExecutionMode.AUTO,ExecutionStatus.RUNNING,old.health(),old.healthError()))); });
    int moves=0;
    while (matches.find(f.match.matchId()).orElseThrow().status()==MatchStatus.ACTIVE && moves<42) {
      var s=lifecycle.start(f.room.roomId()); assertInstanceOf(DecisionOutcome.Submitted.class,runner.run(lifecycle.runtimeInput(s.decisionId()),()->false).toCompletableFuture().join()); moves++;
    }
    var done=matches.find(f.match.matchId()).orElseThrow(); assertEquals(MatchStatus.FINISHED,done.status());
    assertEquals(moves,done.stateVersion()); assertNull(done.currentTurnId());
    assertEquals(ExecutionStatus.PAUSED,rooms.find(f.room.roomId()).orElseThrow().executionStatus());
  }
  @Test void runnerClaimIsSharedAndPreventsLateOrDuplicateRuntimeCalls() {
    Fixture f=fixture(); var s=lifecycle.start(f.room.roomId()); var input=lifecycle.runtimeInput(s.decisionId());
    var calls=new AtomicInteger(); var pending=new CompletableFuture<RuntimeOutcome>();
    AgentRuntime runtime=(i,c)->{ calls.incrementAndGet(); return pending; };
    AgentRunner one=new DefaultAgentRunner(runtime,lifecycle,submitter,new DecisionInputReader(),json,UUID::randomUUID);
    AgentRunner two=new DefaultAgentRunner(runtime,lifecycle,submitter,new DecisionInputReader(),json,UUID::randomUUID);
    var first=one.run(input,()->false); var duplicate=(DecisionOutcome.NotSubmitted)two.run(input,()->false).toCompletableFuture().join();
    assertEquals("DECISION_ALREADY_CLAIMED",duplicate.error().code()); assertEquals(1,calls.get());
    pending.complete(new RuntimeOutcome.SelectedAction("c0")); assertInstanceOf(DecisionOutcome.Submitted.class,first.toCompletableFuture().join());
    assertEquals("DECISION_NOT_RUNNING",((DecisionOutcome.NotSubmitted)two.run(input,()->false).toCompletableFuture().join()).error().code());
    assertEquals(1,calls.get()); assertTrue(lifecycle.executionEnvelope(s.decisionId()).isPresent());
  }
  @Test void expirationAndCancellationBeforeAndAfterRuntimeDoNotSubmit() {
    Fixture f=fixture(); var s=lifecycle.start(f.room.roomId()); var calls=new AtomicInteger();
    AgentRuntime runtime=(i,c)->{ calls.incrementAndGet(); return new CompletableFuture<>(); };
    var runner=new DefaultAgentRunner(runtime,lifecycle,submitter,new DecisionInputReader(),json,UUID::randomUUID);
    var clock=(MutableClock)this.clock; clock.set(s.deadline());
    try { assertEquals("DECISION_EXPIRED",((DecisionOutcome.NotSubmitted)runner.run(lifecycle.runtimeInput(s.decisionId()),()->false).toCompletableFuture().join()).error().code()); }
    finally { clock.set(MutableClock.INITIAL); }
    assertEquals(0,calls.get()); assertEquals(DecisionStatus.EXPIRED,decisions.find(s.decisionId()).orElseThrow().status());
    Fixture other=fixture(); var c=lifecycle.start(other.room.roomId()); var flag=new AtomicBoolean(); var result=new CompletableFuture<RuntimeOutcome>();
    var delayed=new DefaultAgentRunner((i,signal)->result,lifecycle,submitter,new DecisionInputReader(),json,UUID::randomUUID);
    var future=delayed.run(lifecycle.runtimeInput(c.decisionId()),flag::get); flag.set(true); result.complete(new RuntimeOutcome.SelectedAction("c0"));
    assertInstanceOf(DecisionOutcome.NotSubmitted.class,future.toCompletableFuture().join());
    assertEquals(DecisionStatus.CANCELLED,decisions.find(c.decisionId()).orElseThrow().status());
    assertEquals(TurnStatus.CANCELLED,turns.find(c.turnId()).orElseThrow().status());
    Fixture third=fixture(); var t=lifecycle.start(third.room.roomId());
    runner.run(lifecycle.runtimeInput(t.decisionId()),()->true).toCompletableFuture().join();
    assertEquals(DecisionStatus.CANCELLED,decisions.find(t.decisionId()).orElseThrow().status()); assertEquals(0,calls.get());
  }
  @Test void roomCannotStartOrCancelAnotherRoomsMatch() {
    Fixture a=fixture(), b=fixture();
    var foreign=lifecycle.start(b.room.roomId());
    Room roomA=rooms.find(a.room.roomId()).orElseThrow(), roomB=rooms.find(b.room.roomId()).orElseThrow();
    Match matchA=matches.find(a.match.matchId()).orElseThrow(), matchB=matches.find(b.match.matchId()).orElseThrow();
    TurnContext turnB=turns.find(foreign.turnId()).orElseThrow();
    var eventsA=roomEvents.readAfter(roomA.roomId(),0,100); var eventsB=roomEvents.readAfter(roomB.roomId(),0,100);

    assertThrows(StorageConflictException.class,()->new TransactionTemplate(gameTransactionManager).executeWithoutResult(tx->{
      assertTrue(rooms.replace(roomA,new Room(roomA.roomId(),roomA.revision(),roomA.eventSeq(),matchB.matchId(),roomA.executionMode(),roomA.executionStatus(),roomA.health(),roomA.healthError())));
    }));
    assertEquals(roomA,rooms.find(roomA.roomId()).orElseThrow());
    assertEquals(roomB,rooms.find(roomB.roomId()).orElseThrow());
    assertEquals(matchA,matches.find(matchA.matchId()).orElseThrow()); assertEquals(matchB,matches.find(matchB.matchId()).orElseThrow());

    faultGate.projectedRoomId=roomA.roomId(); faultGate.projectedActiveMatchId=matchB.matchId();
    try {
      var startError=assertThrows(IllegalStateException.class,()->lifecycle.start(roomA.roomId()));
      assertEquals("MATCH_ROOM_MISMATCH",startError.getMessage());
      var cancelError=assertThrows(IllegalStateException.class,()->lifecycle.cancel(roomA.roomId(),UUID.randomUUID()));
      assertEquals("MATCH_ROOM_MISMATCH",cancelError.getMessage());
    } finally { faultGate.projectedRoomId=null; faultGate.projectedActiveMatchId=null; }

    assertEquals(roomA,rooms.find(roomA.roomId()).orElseThrow()); assertEquals(roomB,rooms.find(roomB.roomId()).orElseThrow());
    assertEquals(matchA,matches.find(matchA.matchId()).orElseThrow()); assertEquals(matchB,matches.find(matchB.matchId()).orElseThrow());
    assertEquals(turnB,turns.find(turnB.turnId()).orElseThrow());
    assertEquals(DecisionStatus.RUNNING,decisions.find(foreign.decisionId()).orElseThrow().status());
    assertEquals(eventsA,roomEvents.readAfter(roomA.roomId(),0,100)); assertEquals(eventsB,roomEvents.readAfter(roomB.roomId(),0,100));
  }
  @Test void numericCategoryIsPartOfIdempotencyInBothDirections() {
    for (boolean decimalFirst:List.of(false,true)) {
      Fixture f=fixture(); var s=lifecycle.start(f.room.roomId()); var id=UUID.randomUUID();
      Object firstValue=decimalFirst?(Object)Double.valueOf(0):Integer.valueOf(0);
      Object otherValue=decimalFirst?(Object)Integer.valueOf(0):Double.valueOf(0);
      var a=new ActionEnvelope("1.0",s.matchId(),s.participantId(),s.turnId(),s.decisionId(),id,0,Map.of("type","place_disc","column",firstValue));
      var b=new ActionEnvelope("1.0",s.matchId(),s.participantId(),s.turnId(),s.decisionId(),id,0,Map.of("type","place_disc","column",otherValue));
      var result=submitter.submit(a).toCompletableFuture().join();
      assertEquals(decimalFirst?SubmissionStatus.REJECTED:SubmissionStatus.EXECUTED,result.status());
      assertEquals(result,submitter.submit(a).toCompletableFuture().join());
      assertEquals("ACTION_ID_CONFLICT",submitter.submit(b).toCompletableFuture().join().errorCode());
    }
  }
  @Test void roomEventsUsePublishedProtocolAndOneRevisionPerSettlement() {
    Fixture f=fixture(); var s=lifecycle.start(f.room.roomId()); var start=roomEvents.readAfter(f.room.roomId(),0,100).get(0);
    assertEquals("DECISION_STARTED",start.type()); assertEquals(s.participantId().toString(),start.payload().get("participantId"));
    assertEquals(s.promptVersionId().toString(),start.payload().get("promptVersionId"));
    runner.run(lifecycle.runtimeInput(s.decisionId()),()->false).toCompletableFuture().join();
    var all=roomEvents.readAfter(f.room.roomId(),0,100);
    assertEquals(List.of("DECISION_STARTED","DECISION_FINISHED","ACTION_RESOLVED"),all.stream().map(RoomEvent::type).toList());
    assertEquals("COMPLETED",all.get(1).payload().get("status")); assertEquals(all.get(1).roomRevision(),all.get(2).roomRevision());
    assertEquals(2,rooms.find(f.room.roomId()).orElseThrow().revision()); assertEquals(3,rooms.find(f.room.roomId()).orElseThrow().eventSeq());
  }
  @Test void claimFailureRollsBackAndDoesNotInvokeRuntime() {
    Fixture f=fixture(); var s=lifecycle.start(f.room.roomId()); var calls=new AtomicInteger();
    var r=new DefaultAgentRunner((i,c)->{calls.incrementAndGet();return CompletableFuture.completedFuture(new RuntimeOutcome.SelectedAction("c0"));},lifecycle,submitter,new DecisionInputReader(),json,UUID::randomUUID);
    faultGate.target="claim.claim";
    try { assertThrows(IllegalStateException.class,()->r.run(lifecycle.runtimeInput(s.decisionId()),()->false)); }
    finally { faultGate.target=null; }
    assertEquals(0,calls.get()); assertEquals(DecisionStatus.RUNNING,decisions.find(s.decisionId()).orElseThrow().status());
    assertInstanceOf(DecisionOutcome.Submitted.class,r.run(lifecycle.runtimeInput(s.decisionId()),()->false).toCompletableFuture().join());
    assertEquals(1,calls.get());
  }
  @ParameterizedTest @ValueSource(strings={"decision.insert","inputs.save","record.insert","room.replace","roomEvent.append"})
  void startWriteFailuresRollBackAllFacts(String name) {
    Fixture f=fixture(); var before=rooms.find(f.room.roomId()).orElseThrow(); faultGate.target=name;
    try { assertThrows(IllegalStateException.class,()->lifecycle.start(f.room.roomId())); }
    finally { faultGate.target=null; }
    assertEquals(before,rooms.find(f.room.roomId()).orElseThrow()); assertTrue(decisions.findByTurn(f.match.currentTurnId()).isEmpty());
    assertTrue(roomEvents.readAfter(f.room.roomId(),0,100).isEmpty());
    assertNotNull(lifecycle.start(f.room.roomId()));
  }
  @Test void bindingPointerMovesWithoutChangingRunningInput() {
    Fixture f=fixture(); bindingSource.version.set(1); var s=lifecycle.start(f.room.roomId()); var original=lifecycle.runtimeInput(s.decisionId());
    bindingSource.version.set(2); assertEquals(original,lifecycle.runtimeInput(s.decisionId()));
    lifecycle.fail(s.decisionId(),"RETRY");
    new TransactionTemplate(gameTransactionManager).executeWithoutResult(tx->{var room=rooms.find(f.room.roomId()).orElseThrow();
      assertTrue(rooms.replace(room,new Room(room.roomId(),room.revision(),room.eventSeq(),room.activeMatchId(),room.executionMode(),ExecutionStatus.RUNNING,room.health(),room.healthError())));});
    var next=lifecycle.start(f.room.roomId()); var newer=lifecycle.runtimeInput(next.decisionId());
    assertNotEquals(original.hostedBindings().orElseThrow().prompt().promptVersionId(),newer.hostedBindings().orElseThrow().prompt().promptVersionId());
    assertEquals(original,lifecycle.runtimeInput(s.decisionId()));
  }
  @Test void submissionAndExpirationRespectBothDeterministicOrders() {
    Fixture first=fixture(); var s=lifecycle.start(first.room.roomId()); var e=new ActionEnvelope("1.0",s.matchId(),s.participantId(),s.turnId(),s.decisionId(),UUID.randomUUID(),0,Map.of("type","place_disc","column",0));
    assertEquals(SubmissionStatus.EXECUTED,submitter.submit(e).toCompletableFuture().join().status());
    var clock=(MutableClock)this.clock; clock.set(s.deadline());
    try { lifecycle.expire(s.decisionId()); assertEquals(DecisionStatus.COMPLETED,decisions.find(s.decisionId()).orElseThrow().status()); }
    finally { clock.set(MutableClock.INITIAL); }
    Fixture second=fixture(); var t=lifecycle.start(second.room.roomId()); var late=new ActionEnvelope("1.0",t.matchId(),t.participantId(),t.turnId(),t.decisionId(),UUID.randomUUID(),0,Map.of("type","place_disc","column",0));
    clock.set(t.deadline());
    try { lifecycle.expire(t.decisionId()); assertEquals(SubmissionStatus.REJECTED,submitter.submit(late).toCompletableFuture().join().status());
      assertEquals(DecisionStatus.EXPIRED,decisions.find(t.decisionId()).orElseThrow().status()); assertEquals(0,matches.find(t.matchId()).orElseThrow().stateVersion()); }
    finally { clock.set(MutableClock.INITIAL); }
  }
  @Test void submissionChecksDeadlineAfterWaitingForRoomLock() throws Exception {
    Fixture f=fixture(); var s=lifecycle.start(f.room.roomId());
    var envelope=new ActionEnvelope("1.0",s.matchId(),s.participantId(),s.turnId(),s.decisionId(),UUID.randomUUID(),0,Map.of("type","place_disc","column",0));
    var holding=new CountDownLatch(1); var release=new CountDownLatch(1); var trying=new CountDownLatch(1);
    var pool=Executors.newFixedThreadPool(2); var mutable=(MutableClock)clock;
    try {
      var holder=pool.submit(()->new TransactionTemplate(gameTransactionManager).executeWithoutResult(tx->{ rooms.lock(f.room.roomId()); holding.countDown();
        try { if (!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("barrier timeout"); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); } }));
      assertTrue(holding.await(5,TimeUnit.SECONDS));
      var submit=pool.submit(()->{trying.countDown();return submitter.submit(envelope).toCompletableFuture().join();});
      assertTrue(trying.await(5,TimeUnit.SECONDS)); mutable.set(s.deadline()); release.countDown();
      holder.get(5,TimeUnit.SECONDS); assertEquals("DECISION_EXPIRED",submit.get(5,TimeUnit.SECONDS).errorCode());
      assertEquals(DecisionStatus.EXPIRED,decisions.find(s.decisionId()).orElseThrow().status());
      assertEquals(0,matches.find(s.matchId()).orElseThrow().stateVersion());
    } finally { release.countDown(); pool.shutdownNow(); mutable.set(MutableClock.INITIAL); }
  }
  @Test void submissionAndCancellationRespectBothOrdersAndDeadline() {
    Fixture first=fixture(); var s=lifecycle.start(first.room.roomId()); var e=new ActionEnvelope("1.0",s.matchId(),s.participantId(),s.turnId(),s.decisionId(),UUID.randomUUID(),0,Map.of("type","place_disc","column",0));
    submitter.submit(e).toCompletableFuture().join(); lifecycle.cancel(first.room.roomId(),UUID.randomUUID());
    assertEquals(DecisionStatus.COMPLETED,decisions.find(s.decisionId()).orElseThrow().status());
    Fixture second=fixture(); var t=lifecycle.start(second.room.roomId()); lifecycle.cancel(second.room.roomId(),UUID.randomUUID());
    var late=new ActionEnvelope("1.0",t.matchId(),t.participantId(),t.turnId(),t.decisionId(),UUID.randomUUID(),0,Map.of("type","place_disc","column",0));
    assertEquals(SubmissionStatus.REJECTED,submitter.submit(late).toCompletableFuture().join().status());
    assertEquals(DecisionStatus.CANCELLED,decisions.find(t.decisionId()).orElseThrow().status());
    Fixture third=fixture(); var u=lifecycle.start(third.room.roomId()); var clock=(MutableClock)this.clock; clock.set(u.deadline());
    try { lifecycle.cancel(third.room.roomId(),UUID.randomUUID()); assertEquals(DecisionStatus.EXPIRED,decisions.find(u.decisionId()).orElseThrow().status());
      assertEquals(TurnStatus.OPEN,turns.find(u.turnId()).orElseThrow().status()); }
    finally { clock.set(MutableClock.INITIAL); }
  }
  @ParameterizedTest @ValueSource(strings={"decision.replace","record.replace","turn.replace","match.replace","room.replace"})
  void falseReplaceRollsBackSettlement(String name) {
    Fixture f=fixture(); var s=lifecycle.start(f.room.roomId());
    var envelope=new ActionEnvelope("1.0",s.matchId(),s.participantId(),s.turnId(),s.decisionId(),UUID.randomUUID(),0,Map.of("type","place_disc","column",0));
    var before=rooms.find(f.room.roomId()).orElseThrow(); faultGate.falseTarget=name;
    try { assertThrows(StorageConflictException.class,()->submitter.submit(envelope)); }
    finally { faultGate.falseTarget=null; }
    assertEquals(before,rooms.find(f.room.roomId()).orElseThrow()); assertEquals(0,matches.find(s.matchId()).orElseThrow().stateVersion());
    assertTrue(receipts.findByActionId(envelope.actionId()).isEmpty()); assertTrue(settlements.findByTurnId(s.turnId()).isEmpty());
    assertEquals(DecisionStatus.RUNNING,decisions.find(s.decisionId()).orElseThrow().status());
    assertEquals(SubmissionStatus.EXECUTED,submitter.submit(envelope).toCompletableFuture().join().status());
  }
  @Test void oldReceiptReplaysAfterSameRoomMatchReset() {
    Fixture f=fixture(); var s=lifecycle.start(f.room.roomId()); var e=new ActionEnvelope("1.0",s.matchId(),s.participantId(),s.turnId(),s.decisionId(),UUID.randomUUID(),0,Map.of("type","place_disc","column",0));
    var original=submitter.submit(e).toCompletableFuture().join();
    var oldParticipants=f.match.participants();
    var participants=oldParticipants.stream().map(p->new Participant(UUID.randomUUID(),p.agentId(),p.seatKey())).toList();
    var oldParticipantIds=oldParticipants.stream().map(Participant::participantId).collect(java.util.stream.Collectors.toSet());
    var newParticipantIds=participants.stream().map(Participant::participantId).collect(java.util.stream.Collectors.toSet());
    assertTrue(Collections.disjoint(oldParticipantIds,newParticipantIds));
    for (var oldParticipant:oldParticipants) {
      var resetParticipant=participants.stream().filter(p->p.seatKey().equals(oldParticipant.seatKey())).findFirst().orElseThrow();
      assertEquals(oldParticipant.agentId(),resetParticipant.agentId());
      assertNotEquals(oldParticipant.participantId(),resetParticipant.participantId());
    }
    var initialized=new RegisteredMatchFactory(registry,json,clock,UUID::randomUUID).create(f.room.roomId(),f.match.gameKey(),participants);
    new TransactionTemplate(gameTransactionManager).executeWithoutResult(tx->{
      Match old=matches.find(f.match.matchId()).orElseThrow();
      TurnContext oldCurrent=turns.find(old.currentTurnId()).orElseThrow();
      if (oldCurrent.status()==TurnStatus.OPEN)
        assertTrue(turns.replace(oldCurrent,new TurnContext(oldCurrent.turnId(),oldCurrent.turnNo(),oldCurrent.roundNo(),oldCurrent.baseStateVersion(),oldCurrent.schedulingMode(),oldCurrent.requiredParticipants(),TurnStatus.CANCELLED)));
      assertTrue(matches.replace(old,new Match(old.matchId(),old.roomId(),old.gameKey(),MatchStatus.ABORTED,old.stateVersion(),old.gameEventSeq(),null,old.participants(),old.result())));
      matches.insert(initialized.match()); turns.insert(initialized.match().matchId(),initialized.firstTurn());
      snapshots.append(initialized.initialSnapshot());
      Room room=rooms.find(f.room.roomId()).orElseThrow();
      assertTrue(rooms.replace(room,new Room(room.roomId(),room.revision(),room.eventSeq(),initialized.match().matchId(),room.executionMode(),ExecutionStatus.PAUSED,room.health(),room.healthError())));
    });
    assertEquals(original,submitter.submit(e).toCompletableFuture().join());
    assertEquals(MatchStatus.ABORTED,matches.find(f.match.matchId()).orElseThrow().status());
    assertEquals(1,matches.find(f.match.matchId()).orElseThrow().stateVersion());
    assertEquals(1,settlements.findByTurnId(s.turnId()).orElseThrow().toVersion());
    Match active=matches.find(initialized.match().matchId()).orElseThrow();
    assertEquals(0,active.stateVersion()); assertEquals(TurnStatus.OPEN,turns.find(active.currentTurnId()).orElseThrow().status());
    assertEquals(0,turns.find(active.currentTurnId()).orElseThrow().baseStateVersion());
  }
  @Test void submitFailureRetainsEnvelopeForRetryWithoutAnotherRuntimeInvocation() {
    Fixture f=fixture(); var s=lifecycle.start(f.room.roomId()); var calls=new AtomicInteger();
    var r=new DefaultAgentRunner((i,c)->{calls.incrementAndGet();return CompletableFuture.completedFuture(new RuntimeOutcome.SelectedAction("c0"));},lifecycle,submitter,new DecisionInputReader(),json,UUID::randomUUID);
    faultGate.target="gameEvent.append";
    try { assertThrows(CompletionException.class,()->r.run(lifecycle.runtimeInput(s.decisionId()),()->false).toCompletableFuture().join()); }
    finally { faultGate.target=null; }
    var envelope=lifecycle.executionEnvelope(s.decisionId()).orElseThrow();
    assertEquals(SubmissionStatus.EXECUTED,submitter.submit(envelope).toCompletableFuture().join().status());
    assertEquals(1,calls.get());
  }
  @ParameterizedTest @ValueSource(strings={"record.replace","claim.saveEnvelope"})
  void outputPersistenceFailureCanResumeAcrossRunners(String fault) {
    Fixture f=fixture(); var s=lifecycle.start(f.room.roomId()); var input=lifecycle.runtimeInput(s.decisionId());
    var calls=new AtomicInteger(); UUID actionId=UUID.randomUUID();
    AgentRuntime runtime=(i,c)->{calls.incrementAndGet(); return CompletableFuture.completedFuture(new RuntimeOutcome.SelectedAction("c0"));};
    var first=new DefaultAgentRunner(runtime,lifecycle,submitter,new DecisionInputReader(),json,()->actionId);
    var second=new DefaultAgentRunner(runtime,lifecycle,submitter,new DecisionInputReader(),json,UUID::randomUUID);
    faultGate.target=fault;
    try { assertThrows(CompletionException.class,()->first.run(input,()->false).toCompletableFuture().join()); }
    finally { faultGate.target=null; }
    assertEquals(1,calls.get()); assertTrue(lifecycle.executionEnvelope(s.decisionId()).isEmpty());
    var result=(DecisionOutcome.Submitted)second.run(input,()->false).toCompletableFuture().join();
    assertEquals(actionId,result.submission().actionId()); assertEquals(SubmissionStatus.EXECUTED,result.submission().status());
    assertEquals(1,calls.get()); assertEquals(actionId,lifecycle.executionEnvelope(s.decisionId()).orElseThrow().actionId());
  }
  @Test void repeatedFailuresAndConcurrentRecoveryReuseOneRuntimeAndOneAction() throws Exception {
    Fixture f=fixture(); var s=lifecycle.start(f.room.roomId()); var input=lifecycle.runtimeInput(s.decisionId());
    var calls=new AtomicInteger(); UUID actionId=UUID.randomUUID();
    AgentRuntime runtime=(i,c)->{calls.incrementAndGet();return CompletableFuture.completedFuture(new RuntimeOutcome.SelectedAction("c0"));};
    var first=new DefaultAgentRunner(runtime,lifecycle,submitter,new DecisionInputReader(),json,()->actionId);
    var other=new DefaultAgentRunner(runtime,lifecycle,submitter,new DecisionInputReader(),json,UUID::randomUUID);
    faultGate.target="record.replace";
    try { assertThrows(CompletionException.class,()->first.run(input,()->false).toCompletableFuture().join()); }
    finally { faultGate.target=null; }
    faultGate.target="claim.saveEnvelope";
    try { assertThrows(IllegalStateException.class,()->other.run(input,()->false)); }
    finally { faultGate.target=null; }
    var ready=new CountDownLatch(2); var go=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
    try {
      Callable<DecisionOutcome> taskA=()->{ready.countDown(); go.await(); return first.run(input,()->false).toCompletableFuture().join();};
      Callable<DecisionOutcome> taskB=()->{ready.countDown(); go.await(); return other.run(input,()->false).toCompletableFuture().join();};
      var a=pool.submit(taskA); var b=pool.submit(taskB); assertTrue(ready.await(5,TimeUnit.SECONDS)); go.countDown();
      var outcomes=List.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS));
      assertEquals(1,outcomes.stream().filter(DecisionOutcome.Submitted.class::isInstance).count());
      assertEquals(actionId,((DecisionOutcome.Submitted)outcomes.stream().filter(DecisionOutcome.Submitted.class::isInstance).findFirst().orElseThrow()).submission().actionId());
      assertEquals(1,calls.get()); assertEquals(1,matches.find(s.matchId()).orElseThrow().stateVersion());
    } finally { pool.shutdownNow(); }
  }
  static java.util.stream.Stream<Arguments> failureFinishCases() {
    return java.util.stream.Stream.of("runtime","sync","async","cancel","unknown","unsupported")
        .flatMap(kind->java.util.stream.Stream.of("decision.replace","record.replace","room.replace","roomEvent.append","false:decision.replace","false:record.replace","false:room.replace")
            .map(fault->Arguments.of(kind,fault)));
  }
  @ParameterizedTest @MethodSource("failureFinishCases")
  void runtimeFailuresRecoverAfterFinishWriteFailures(String kind,String fault) {
    Fixture f=fixture(); var s=lifecycle.start(f.room.roomId()); var input=lifecycle.runtimeInput(s.decisionId()); var calls=new AtomicInteger(); var cancelled=new AtomicBoolean();
    AgentRuntime runtime=(i,c)->{ calls.incrementAndGet(); return switch (kind) {
      case "sync" -> throw new IllegalStateException("runtime");
      case "async" -> CompletableFuture.failedFuture(new IllegalStateException("runtime"));
      case "cancel" -> { cancelled.set(true); yield CompletableFuture.completedFuture(new RuntimeOutcome.SelectedAction("c0")); }
      case "unknown" -> CompletableFuture.completedFuture(new RuntimeOutcome.SelectedAction("unknown"));
      case "unsupported" -> CompletableFuture.completedFuture(new RuntimeOutcome.StructuredAction("{}"));
      default -> CompletableFuture.completedFuture(new RuntimeOutcome.Failed(new RuntimeError("MODEL_UNAVAILABLE","offline")));
    }; };
    var one=new DefaultAgentRunner(runtime,lifecycle,submitter,new DecisionInputReader(),json,UUID::randomUUID);
    var two=new DefaultAgentRunner(runtime,lifecycle,submitter,new DecisionInputReader(),json,UUID::randomUUID);
    if (fault.startsWith("false:")) faultGate.falseTarget=fault.substring(6); else faultGate.target=fault;
    try { assertThrows(RuntimeException.class,()->one.run(input,cancelled::get).toCompletableFuture().join()); }
    finally { faultGate.target=null; faultGate.falseTarget=null; }
    assertEquals(1,calls.get()); assertEquals(DecisionStatus.RUNNING,decisions.find(s.decisionId()).orElseThrow().status());
    var result=(DecisionOutcome.NotSubmitted)two.run(input,()->false).toCompletableFuture().join();
    String expected=switch(kind) { case "runtime" -> "MODEL_UNAVAILABLE"; case "cancel" -> "DECISION_CANCELLED"; case "unknown" -> "UNKNOWN_ACTION_OPTION"; case "unsupported" -> "ACTION_MODE_NOT_SUPPORTED"; default -> "RUNTIME_ERROR"; };
    assertEquals(expected,result.error().code()); assertEquals(1,calls.get());
    assertEquals(kind.equals("cancel")?DecisionStatus.CANCELLED:DecisionStatus.FAILED,decisions.find(s.decisionId()).orElseThrow().status());
    assertTrue(records.find(s.decisionId()).orElseThrow().errorJson().contains(expected));
    var room=rooms.find(f.room.roomId()).orElseThrow(); assertEquals(2,room.revision()); assertEquals(2,room.eventSeq());
    assertEquals("DECISION_FINISHED",roomEvents.readAfter(f.room.roomId(),0,100).get(1).type());
  }
  @Test void repeatedFailureAndConcurrentFailureRecoveryFinishOnce() throws Exception {
    Fixture f=fixture(); var s=lifecycle.start(f.room.roomId()); var input=lifecycle.runtimeInput(s.decisionId()); var calls=new AtomicInteger();
    AgentRuntime runtime=(i,c)->{calls.incrementAndGet();return CompletableFuture.completedFuture(new RuntimeOutcome.Failed(new RuntimeError("MODEL_UNAVAILABLE","offline")));};
    var one=new DefaultAgentRunner(runtime,lifecycle,submitter,new DecisionInputReader(),json,UUID::randomUUID);
    var two=new DefaultAgentRunner(runtime,lifecycle,submitter,new DecisionInputReader(),json,UUID::randomUUID);
    faultGate.target="decision.replace";
    try { assertThrows(RuntimeException.class,()->one.run(input,()->false).toCompletableFuture().join()); }
    finally { faultGate.target=null; }
    faultGate.target="record.replace";
    try { assertThrows(RuntimeException.class,()->two.run(input,()->false)); }
    finally { faultGate.target=null; }
    var ready=new CountDownLatch(2); var go=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
    try {
      Callable<DecisionOutcome> a=()->{ready.countDown();go.await();return one.run(input,()->false).toCompletableFuture().join();};
      Callable<DecisionOutcome> b=()->{ready.countDown();go.await();return two.run(input,()->false).toCompletableFuture().join();};
      var first=pool.submit(a); var second=pool.submit(b); assertTrue(ready.await(5,TimeUnit.SECONDS)); go.countDown();
      var outcomes=List.of(first.get(5,TimeUnit.SECONDS),second.get(5,TimeUnit.SECONDS));
      assertTrue(outcomes.stream().map(x->((DecisionOutcome.NotSubmitted)x).error().code()).anyMatch("MODEL_UNAVAILABLE"::equals));
      assertEquals(1,calls.get()); assertEquals(DecisionStatus.FAILED,decisions.find(s.decisionId()).orElseThrow().status());
      var room=rooms.find(f.room.roomId()).orElseThrow(); assertEquals(2,room.revision()); assertEquals(2,room.eventSeq());
    } finally { pool.shutdownNow(); }
  }
  @ParameterizedTest @ValueSource(strings={"deadline","cancel","reset"})
  void failureRecoveryRespectsTerminalChangesWhileWaiting(String end) throws Exception {
    Fixture f=fixture(); var s=lifecycle.start(f.room.roomId()); var input=lifecycle.runtimeInput(s.decisionId()); var calls=new AtomicInteger();
    AgentRuntime runtime=(i,c)->{calls.incrementAndGet();return CompletableFuture.completedFuture(new RuntimeOutcome.Failed(new RuntimeError("MODEL_UNAVAILABLE","offline")));};
    var one=new DefaultAgentRunner(runtime,lifecycle,submitter,new DecisionInputReader(),json,UUID::randomUUID);
    var two=new DefaultAgentRunner(runtime,lifecycle,submitter,new DecisionInputReader(),json,UUID::randomUUID);
    faultGate.target="decision.replace";
    try { assertThrows(RuntimeException.class,()->one.run(input,()->false).toCompletableFuture().join()); } finally { faultGate.target=null; }
    var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var pool=Executors.newSingleThreadExecutor(); var mutable=(MutableClock)clock;
    try {
      var recovering=pool.submit(()->two.run(input,()->{entered.countDown(); try { if (!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
        catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); } return false; }).toCompletableFuture().join());
      assertTrue(entered.await(5,TimeUnit.SECONDS));
      if (end.equals("deadline")) { mutable.set(s.deadline()); lifecycle.expire(s.decisionId()); }
      if (end.equals("cancel")) lifecycle.cancel(f.room.roomId(),UUID.randomUUID());
      if (end.equals("reset")) { var replacement=new RegisteredMatchFactory(registry,json,clock,UUID::randomUUID).create(f.room.roomId(),f.match.gameKey(),f.match.participants());
        new TransactionTemplate(gameTransactionManager).executeWithoutResult(tx->{var old=rooms.find(f.room.roomId()).orElseThrow(); matches.insert(replacement.match());
          turns.insert(replacement.match().matchId(),replacement.firstTurn()); snapshots.append(replacement.initialSnapshot());
          assertTrue(rooms.replace(old,new Room(old.roomId(),old.revision(),old.eventSeq(),replacement.match().matchId(),old.executionMode(),old.executionStatus(),old.health(),old.healthError())));}); }
      var before=decisions.find(s.decisionId()).orElseThrow(); var room=rooms.find(f.room.roomId()).orElseThrow();
      release.countDown(); assertInstanceOf(DecisionOutcome.NotSubmitted.class,recovering.get(5,TimeUnit.SECONDS));
      assertEquals(before,decisions.find(s.decisionId()).orElseThrow()); assertEquals(room,rooms.find(f.room.roomId()).orElseThrow());
      assertEquals(1,calls.get()); assertEquals(0,matches.find(s.matchId()).orElseThrow().stateVersion());
    } finally { release.countDown(); pool.shutdownNow(); mutable.set(MutableClock.INITIAL); }
  }
  @Test void cancellationDuringCandidateRecoverySurvivesFinishFailure() {
    Fixture f=fixture(); var s=lifecycle.start(f.room.roomId()); var input=lifecycle.runtimeInput(s.decisionId()); var calls=new AtomicInteger();
    AgentRuntime runtime=(i,c)->{calls.incrementAndGet();return CompletableFuture.completedFuture(new RuntimeOutcome.SelectedAction("c0"));};
    var one=new DefaultAgentRunner(runtime,lifecycle,submitter,new DecisionInputReader(),json,UUID::randomUUID);
    var two=new DefaultAgentRunner(runtime,lifecycle,submitter,new DecisionInputReader(),json,UUID::randomUUID);
    faultGate.target="claim.saveEnvelope";
    try { assertThrows(RuntimeException.class,()->one.run(input,()->false).toCompletableFuture().join()); } finally { faultGate.target=null; }
    faultGate.target="decision.replace";
    try { assertThrows(RuntimeException.class,()->two.run(input,()->true)); } finally { faultGate.target=null; }
    assertEquals("DECISION_CANCELLED",((DecisionOutcome.NotSubmitted)two.run(input,()->false).toCompletableFuture().join()).error().code());
    assertEquals(1,calls.get()); assertEquals(DecisionStatus.CANCELLED,decisions.find(s.decisionId()).orElseThrow().status());
    assertEquals(0,matches.find(s.matchId()).orElseThrow().stateVersion());
  }
  @ParameterizedTest @ValueSource(strings={"deadline","cancel","reset"})
  void recoveryDoesNotExecuteAfterDecisionEnds(String end) throws Exception {
    Fixture f=fixture(); var s=lifecycle.start(f.room.roomId()); var input=lifecycle.runtimeInput(s.decisionId());
    var calls=new AtomicInteger(); AgentRuntime runtime=(i,c)->{calls.incrementAndGet();return CompletableFuture.completedFuture(new RuntimeOutcome.SelectedAction("c0"));};
    var one=new DefaultAgentRunner(runtime,lifecycle,submitter,new DecisionInputReader(),json,UUID::randomUUID);
    var two=new DefaultAgentRunner(runtime,lifecycle,submitter,new DecisionInputReader(),json,UUID::randomUUID);
    faultGate.target="record.replace";
    try { assertThrows(CompletionException.class,()->one.run(input,()->false).toCompletableFuture().join()); }
    finally { faultGate.target=null; }
    var clock=(MutableClock)this.clock; var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var pool=Executors.newSingleThreadExecutor();
    try {
      var recovery=pool.submit(()->two.run(input,()->{entered.countDown(); try { if (!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("barrier timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); } return false; }).toCompletableFuture().join());
      assertTrue(entered.await(5,TimeUnit.SECONDS));
      if (end.equals("deadline")) clock.set(s.deadline());
      if (end.equals("cancel")) lifecycle.cancel(f.room.roomId(),UUID.randomUUID());
      if (end.equals("reset")) { var replacement=new RegisteredMatchFactory(registry,json,clock,UUID::randomUUID).create(f.room.roomId(),f.match.gameKey(),f.match.participants());
        new TransactionTemplate(gameTransactionManager).executeWithoutResult(tx->{var old=rooms.find(f.room.roomId()).orElseThrow();
          matches.insert(replacement.match()); turns.insert(replacement.match().matchId(),replacement.firstTurn()); snapshots.append(replacement.initialSnapshot());
          assertTrue(rooms.replace(old,new Room(old.roomId(),old.revision(),old.eventSeq(),replacement.match().matchId(),old.executionMode(),old.executionStatus(),old.health(),old.healthError())));}); }
      var before=matches.find(s.matchId()).orElseThrow();
      release.countDown(); assertInstanceOf(DecisionOutcome.NotSubmitted.class,recovery.get(5,TimeUnit.SECONDS));
      assertEquals(before,matches.find(s.matchId()).orElseThrow()); assertEquals(0,before.stateVersion()); assertEquals(1,calls.get());
      if (end.equals("deadline")) assertEquals(DecisionStatus.EXPIRED,decisions.find(s.decisionId()).orElseThrow().status());
      if (end.equals("cancel")) assertEquals(DecisionStatus.CANCELLED,decisions.find(s.decisionId()).orElseThrow().status());
    } finally { release.countDown(); pool.shutdownNow(); clock.set(MutableClock.INITIAL); }
  }
  @ParameterizedTest @ValueSource(strings={"COMPLETED","FAILED","EXPIRED","CANCELLED"})
  void terminalStateSurvivesLateOperations(String terminal) {
    Fixture f=fixture(); var s=lifecycle.start(f.room.roomId()); var e=new ActionEnvelope("1.0",s.matchId(),s.participantId(),s.turnId(),s.decisionId(),UUID.randomUUID(),0,Map.of("type","place_disc","column",0));
    var mutable=(MutableClock)clock;
    try {
      switch (terminal) {
        case "COMPLETED" -> submitter.submit(e).toCompletableFuture().join();
        case "FAILED" -> lifecycle.fail(s.decisionId(),"TEST_FAILURE");
        case "EXPIRED" -> { mutable.set(s.deadline()); lifecycle.expire(s.decisionId()); }
        case "CANCELLED" -> lifecycle.cancel(f.room.roomId(),UUID.randomUUID());
      }
      var decision=decisions.find(s.decisionId()).orElseThrow(); var match=matches.find(s.matchId()).orElseThrow();
      var room=rooms.find(f.room.roomId()).orElseThrow(); var turn=turns.find(s.turnId()).orElseThrow();
      lifecycle.fail(s.decisionId(),"LATE"); lifecycle.expire(s.decisionId()); lifecycle.runtimeFailed(s.decisionId(),"DECISION_CANCELLED"); lifecycle.cancel(f.room.roomId(),UUID.randomUUID());
      submitter.submit(new ActionEnvelope("1.0",s.matchId(),s.participantId(),s.turnId(),s.decisionId(),UUID.randomUUID(),0,Map.of("type","place_disc","column",0))).toCompletableFuture().join();
      assertEquals(decision,decisions.find(s.decisionId()).orElseThrow()); assertEquals(match,matches.find(s.matchId()).orElseThrow());
      assertEquals(room,rooms.find(f.room.roomId()).orElseThrow()); assertEquals(turn,turns.find(s.turnId()).orElseThrow());
    } finally { mutable.set(MutableClock.INITIAL); }
  }
  private Fixture fixture() {
    UUID roomId=UUID.randomUUID(); var participants=List.of(new Participant(UUID.randomUUID(),UUID.randomUUID(),"A"),new Participant(UUID.randomUUID(),UUID.randomUUID(),"B"));
    var initial=new RegisteredMatchFactory(registry,json,clock,UUID::randomUUID).create(roomId,new GameKey("connect-four","1"),participants);
    Room room=new Room(roomId,0,0,initial.match().matchId(),ExecutionMode.STEP,ExecutionStatus.RUNNING,RoomHealth.OK,null);
    Match m=initial.match(); Match active=new Match(m.matchId(),m.roomId(),m.gameKey(),MatchStatus.ACTIVE,m.stateVersion(),m.gameEventSeq(),m.currentTurnId(),m.participants(),m.result());
    new TransactionTemplate(gameTransactionManager).executeWithoutResult(tx->{rooms.insert(room); matches.insert(active); turns.insert(active.matchId(),initial.firstTurn()); snapshots.append(initial.initialSnapshot());});
    return new Fixture(room,active);
  }
  record Fixture(Room room,Match match) {}
  static class FaultGate { volatile boolean fail; volatile String target; volatile String falseTarget; volatile UUID projectedRoomId; volatile UUID projectedActiveMatchId; volatile CountDownLatch failureEvent; volatile int skip; volatile boolean once; }
  static class BindingSource implements AgentBindingsReader {
    final AtomicInteger version=new AtomicInteger(1); final Clock clock;
    BindingSource(Clock clock) { this.clock=clock; }
    public Optional<HostedBindings> current(UUID agentId) {
      int v=version.get(); UUID prompt=UUID.nameUUIDFromBytes((agentId+"prompt"+v).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      UUID config=UUID.nameUUIDFromBytes((agentId+"config"+v).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return Optional.of(new HostedBindings(new PromptVersion(prompt,agentId,v,v,"prompt"+v,clock.instant()),
          new AgentConfiguration(config,agentId,v,RuntimeType.MOCK,new ModelConfiguration("mock","mock",false,0,100,10000,null,"JSON",false),new MemoryPolicy(MemoryMode.STATELESS),clock.instant())));
    }
    public Optional<HostedBindings> versions(UUID agentId,UUID prompt,UUID config) { return current(agentId).filter(b->b.prompt().promptVersionId().equals(prompt)&&b.configuration().configVersionId().equals(config)); }
  }
  static class MutableClock extends Clock {
    static final Instant INITIAL=Instant.parse("2026-01-01T00:00:00Z");
    private volatile Instant now=INITIAL;
    void set(Instant instant) { now=instant; }
    public ZoneId getZone() { return ZoneOffset.UTC; }
    public Clock withZone(ZoneId zone) { return Clock.fixed(now,zone); }
    public Instant instant() { return now; }
  }
  @Configuration(proxyBeanMethods=false)
  @Import({MemoryStorageConfiguration.class,DecisionExecutionModule.class})
  @PropertySource("classpath:memory-test.properties")
  static class Config {
    @Bean(name="gameTransactionManager") PlatformTransactionManager transactionManager(InMemoryStorage storage) { return new InMemoryTransactionManager(storage); }
    @Bean Clock clock() { return new MutableClock(); }
    @Bean FaultGate faultGate() { return new FaultGate(); }
    @SuppressWarnings("unchecked") static <T> T wrap(Class<T> type,T delegate,FaultGate gate,String name) {
      return (T)java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)->{
        if (Objects.equals(gate.falseTarget,name+"."+method.getName())) return false;
        if ((gate.fail && name.equals("gameEvent") && method.getName().equals("append")) || Objects.equals(gate.target,name+"."+method.getName())) {
          if (gate.skip>0) gate.skip--;
          else { if (gate.once) gate.target=null; throw new IllegalStateException("injected "+name+"."+method.getName()); }
        }
        try {
          Object result=method.invoke(delegate,args);
          if (name.equals("roomEvent") && method.getName().equals("append") && args[0] instanceof RoomEvent event
              && event.payload().containsKey("errorCode") && gate.failureEvent!=null) gate.failureEvent.countDown();
          if (name.equals("room") && method.getName().equals("find") && args!=null && Objects.equals(args[0],gate.projectedRoomId)
              && result instanceof Optional<?> found && found.isPresent()) {
            Room room=(Room)found.get();
            return Optional.of(new Room(room.roomId(),room.revision(),room.eventSeq(),gate.projectedActiveMatchId,room.executionMode(),room.executionStatus(),room.health(),room.healthError()));
          }
          return result;
        } catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
      });
    }
    @Bean @Primary RoomStore roomStore(InMemoryRoomRepository d,FaultGate g) { return wrap(RoomStore.class,d,g,"room"); }
    @Bean @Primary MatchStore matchStore(InMemoryMatchRepository d,FaultGate g) { return wrap(MatchStore.class,d,g,"match"); }
    @Bean @Primary TurnStore turnStore(InMemoryTurnRepository d,FaultGate g) { return wrap(TurnStore.class,d,g,"turn"); }
    @Bean @Primary DecisionStore decisionStore(InMemoryDecisionRepository d,FaultGate g) { return wrap(DecisionStore.class,d,g,"decision"); }
    @Bean @Primary DecisionRecordStore recordStore(InMemoryDecisionRecordRepository d,FaultGate g) { return wrap(DecisionRecordStore.class,d,g,"record"); }
    @Bean @Primary SnapshotStore snapshotStore(InMemorySnapshotStore d,FaultGate g) { return wrap(SnapshotStore.class,d,g,"snapshot"); }
    @Bean @Primary GameEventStore gameEventStore(InMemoryGameEventRepository d,FaultGate g) { return wrap(GameEventStore.class,d,g,"gameEvent"); }
    @Bean @Primary SettlementStore settlementStore(InMemorySettlementRepository d,FaultGate g) { return wrap(SettlementStore.class,d,g,"settlement"); }
    @Bean @Primary ActionReceiptStore receiptStore(InMemoryActionReceiptRepository d,FaultGate g) { return wrap(ActionReceiptStore.class,d,g,"receipt"); }
    @Bean @Primary RoomEventRepository roomEventStore(InMemoryRoomEventRepository d,FaultGate g) { return wrap(RoomEventRepository.class,d,g,"roomEvent"); }
    @Bean @Primary DecisionExecutionClaimStore claimStore(InMemoryDecisionExecutionClaimStore d,FaultGate g) { return wrap(DecisionExecutionClaimStore.class,d,g,"claim"); }
    @Bean @Primary DecisionInputSnapshotStore inputStore(InMemoryDecisionInputSnapshotStore d,FaultGate g) { return wrap(DecisionInputSnapshotStore.class,d,g,"inputs"); }
    @Bean Supplier<UUID> ids() { return UUID::randomUUID; }
    @Bean JsonMapper json() { return JsonMapper.builder().build(); }
    @Bean DecisionInputReader reader() { return new DecisionInputReader(); }
    @Bean AgentRuntime runtime(DecisionInputReader reader,Clock clock) { return new MockAgentRuntime(reader,clock); }
    @Bean GameRegistry registry() { return new DefaultGameRegistry(List.of(new GameRegistration<>(new ConnectFourDefinition(),new ConnectFourCodec(),new ConnectFourActionSchemaProvider()),ThreeSeatGameFixture.registration())); }
    @Bean DecisionGameMetadata metadata() { return new ConnectFourDecisionMetadata(); }
    @Bean DecisionGameMetadata fixtureMetadata() { return ThreeSeatGameFixture.metadata(); }
    @Bean BindingSource bindings(Clock clock) { return new BindingSource(clock); }
  }
}
