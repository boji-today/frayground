package io.frayground.application;

import static org.junit.jupiter.api.Assertions.*;
import io.frayground.adapter.game.RegisteredMatchFactory;
import io.frayground.adapter.memory.InMemoryCommandReceiptRepository;
import io.frayground.agent.*;
import io.frayground.agent.input.DecisionInputReader;
import io.frayground.application.port.*;
import io.frayground.domain.action.*;
import io.frayground.domain.game.*;
import io.frayground.domain.match.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Exercises the scheduler through the real transactional memory adapter and runner. */
@SpringJUnitConfig(TurnSchedulingIntegrationTest.Config.class)
class TurnSchedulingIntegrationTest {
  @Autowired DefaultTurnScheduler scheduler; @Autowired ControlledRuntime runtime;
  @Autowired RoomCommandService commands;
  @Autowired RoomStore rooms; @Autowired MatchStore matches; @Autowired TurnStore turns;
  @Autowired DecisionStore decisions; @Autowired SnapshotStore snapshots; @Autowired RoomEventRepository events;
  @Autowired DecisionRecordStore records; @Autowired SettlementStore settlements; @Autowired ActionReceiptStore actionReceipts;
  @Autowired MatchFactory factory; @Autowired PlatformTransactionManager gameTransactionManager;
  @Autowired DecisionExecutionIntegrationTest.FaultGate faults;
  @Autowired DecisionExecutionService lifecycle;
  @Autowired ActionSubmitter submitter; @Autowired DecisionInputReader inputReader; @Autowired JsonMapper json;
  @Autowired DecisionInputSnapshotStore inputSnapshots; @Autowired RoomTickService tickService;
  @Autowired ManualDeadlines deadlines; @Autowired Clock clock;
  @Autowired RejectingControlExecutor controlExecutor;

  record Call(RuntimeInput input,CompletableFuture<RuntimeOutcome> future,CompletableFuture<Void> finished) {}
  static class ControlledRuntime implements AgentRuntime {
    final BlockingQueue<Call> calls=new LinkedBlockingQueue<>();
    final ConcurrentMap<UUID,Call> byDecision=new ConcurrentHashMap<>();
    final ConcurrentMap<UUID,java.util.concurrent.atomic.AtomicInteger> invocations=new ConcurrentHashMap<>();
    final Queue<Integer> immediate=new ConcurrentLinkedQueue<>();
    volatile CountDownLatch recoveryDone;
    public CompletionStage<RuntimeOutcome> decide(RuntimeInput input,CancellationSignal signal) {
      invocations.computeIfAbsent(input.publicRequest().decisionId(),key->new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
      var future=new CompletableFuture<RuntimeOutcome>(); var call=new Call(input,future,new CompletableFuture<>());
      byDecision.put(input.publicRequest().decisionId(),call); calls.add(call);
      Integer selected=immediate.poll(); if (selected!=null) future.complete(new RuntimeOutcome.SelectedAction("c"+selected));
      return future;
    }
    Call next() throws InterruptedException { Call call=calls.poll(5,TimeUnit.SECONDS); assertNotNull(call,"decision did not start"); return call; }
  }
  static class ManualDeadlines extends ScheduledThreadPoolExecutor {
    record Task(Runnable runnable,long delayMillis) {}
    final BlockingQueue<Task> scheduled=new LinkedBlockingQueue<>();
    ManualDeadlines() { super(1); }
    @Override public ScheduledFuture<?> schedule(Runnable task,long delay,TimeUnit unit) {
      scheduled.add(new Task(task,unit.toMillis(delay)));
      return new ScheduledFuture<Object>() {
        public long getDelay(TimeUnit u) { return 0; }
        public int compareTo(Delayed other) { return 0; }
        public boolean cancel(boolean interrupt) { return false; }
        public boolean isCancelled() { return false; }
        public boolean isDone() { return false; }
        public Object get() { throw new UnsupportedOperationException(); }
        public Object get(long timeout,TimeUnit unit) { throw new UnsupportedOperationException(); }
      };
    }
    void firePending() { int count=scheduled.size(); for (int i=0;i<count;i++) { Task r=scheduled.poll(); if (r!=null) r.runnable().run(); } }
    void awaitScheduled() throws InterruptedException { Task task=scheduled.poll(5,TimeUnit.SECONDS); assertNotNull(task,"retry was not scheduled"); scheduled.add(task); }
    @Override public List<Runnable> shutdownNow() { scheduled.clear(); return super.shutdownNow(); }
  }
  static class RejectingControlExecutor extends AbstractExecutorService {
    final ExecutorService delegate=Executors.newCachedThreadPool();
    final java.util.concurrent.atomic.AtomicBoolean rejectNext=new java.util.concurrent.atomic.AtomicBoolean();
    volatile CountDownLatch rejection=new CountDownLatch(0);
    volatile CompletableFuture<Void> drainFinished=CompletableFuture.completedFuture(null);
    public void execute(Runnable task) {
      if (rejectNext.compareAndSet(true,false)) { rejection.countDown(); throw new RejectedExecutionException("injected control rejection"); }
      var finished=new CompletableFuture<Void>(); drainFinished=finished;
      delegate.execute(()->{ try { task.run(); } finally { finished.complete(null); } });
    }
    void awaitIdle() throws Exception { drainFinished.get(5,TimeUnit.SECONDS); }
    void armRejection() { rejection=new CountDownLatch(1); rejectNext.set(true); }
    public void shutdown() { delegate.shutdown(); }
    public List<Runnable> shutdownNow() { return delegate.shutdownNow(); }
    public boolean isShutdown() { return delegate.isShutdown(); }
    public boolean isTerminated() { return delegate.isTerminated(); }
    public boolean awaitTermination(long timeout,TimeUnit unit) throws InterruptedException { return delegate.awaitTermination(timeout,unit); }
  }
  @Configuration(proxyBeanMethods=false)
  @Import({DecisionExecutionIntegrationTest.Config.class,TurnSchedulingModule.class})
  static class Config {
    @Bean MatchFactory matchFactory(io.frayground.adapter.game.GameRegistry registry,JsonMapper json,Clock clock,DecisionExecutionIntegrationTest.FaultGate gate) {
      return DecisionExecutionIntegrationTest.Config.wrap(MatchFactory.class,new RegisteredMatchFactory(registry,json,clock,UUID::randomUUID),gate,"factory");
    }
    @Bean RecoveryService recovery() { return ()->{}; }
    @Bean(name="decisionDeadlineExecutor",destroyMethod="") ManualDeadlines deadlines() { return new ManualDeadlines(); }
    @Bean(name="roomControlExecutor",destroyMethod="") RejectingControlExecutor controlExecutor() { return new RejectingControlExecutor(); }
    @Bean @Primary CommandReceiptStore commandReceipts(InMemoryCommandReceiptRepository delegate,DecisionExecutionIntegrationTest.FaultGate gate) {
      return DecisionExecutionIntegrationTest.Config.wrap(CommandReceiptStore.class,delegate,gate,"commandReceipt");
    }
    @Bean ControlledRuntime controlledRuntime() { return new ControlledRuntime(); }
    @Bean @Primary AgentRunner controlledRunner(ControlledRuntime runtime,DecisionExecutionService lifecycle,
        ActionSubmitter submitter,DecisionInputReader reader,JsonMapper json) {
      var runner=new DefaultAgentRunner(runtime,lifecycle,submitter,reader,json,UUID::randomUUID);
      return (input,signal)-> {
        var result=runner.run(input,signal);
        var call=runtime.byDecision.get(input.publicRequest().decisionId());
        if (call!=null) result.whenComplete((value,error)->{ call.finished().complete(null); if (runtime.recoveryDone!=null) runtime.recoveryDone.countDown(); });
        return result;
      };
    }
  }
  private Room fixture() {
    UUID roomId=UUID.randomUUID();
    var participants=List.of(new Participant(UUID.randomUUID(),UUID.randomUUID(),"A"),new Participant(UUID.randomUUID(),UUID.randomUUID(),"B"));
    var initial=factory.create(roomId,new GameKey("connect-four","1"),participants);
    var room=new Room(roomId,0,0,initial.match().matchId(),ExecutionMode.STEP,ExecutionStatus.PAUSED,RoomHealth.OK,null);
    new TransactionTemplate(gameTransactionManager).executeWithoutResult(tx->{ rooms.insert(room); matches.insert(initial.match()); turns.insert(initial.match().matchId(),initial.firstTurn()); snapshots.append(initial.initialSnapshot()); });
    return room;
  }
  private CommandResult command(UUID roomId,ControlCommandType type,ExecutionMode mode) {
    Room room=rooms.find(roomId).orElseThrow();
    return scheduler.handleCommand(roomId,new ControlCommand(UUID.randomUUID(),room.revision(),room.activeMatchId(),type,mode));
  }
  private CommandResult cleanupCommand(UUID roomId,ControlCommandType type) {
    for (int i=0;i<10;i++) { CommandResult result=command(roomId,type,null); if (!"ROOM_REVISION_CONFLICT".equals(result.errorCode())) return result; }
    throw new AssertionError("cleanup could not obtain current revision");
  }
  private void choose(Call call,int column) throws Exception {
    call.future().complete(new RuntimeOutcome.SelectedAction("c"+column)); call.finished().get(5,TimeUnit.SECONDS);
  }
  private void assertSettlement(Call call,long version) {
    var request=call.input().publicRequest();
    var decision=decisions.find(request.decisionId()).orElseThrow(); assertEquals(DecisionStatus.COMPLETED,decision.status());
    var receipt=records.find(request.decisionId()).orElseThrow().submissionResult();
    assertEquals(SubmissionStatus.EXECUTED,receipt.status()); assertEquals(version,receipt.stateVersion());
    assertEquals(receipt,actionReceipts.findByActionId(receipt.actionId()).orElseThrow());
    var settlement=settlements.findByTurnId(request.turnId()).orElseThrow();
    assertEquals(version-1,settlement.fromVersion()); assertEquals(version,settlement.toVersion());
    assertEquals(1,settlement.actions().size()); assertEquals(receipt.actionId(),settlement.actions().get(0).actionId());
    assertEquals(request.decisionId(),settlement.actions().get(0).decisionId());
    assertTrue(snapshots.find(request.matchId(),version).isPresent());
    assertEquals(TurnStatus.RESOLVED,turns.find(request.turnId()).orElseThrow().status());
  }
  @Test void startRunsCompleteWinAndTerminalResetPreservesHistory() throws Exception {
    Room room=fixture(); assertNull(command(room.roomId(),ControlCommandType.START,null).errorCode());
    int[] moves={0,1,0,1,0,1,0};
    long version=0; for (int move:moves) { Call call=runtime.next(); choose(call,move); assertSettlement(call,++version); }
    Match finished=matches.find(room.activeMatchId()).orElseThrow();
    assertEquals(MatchStatus.FINISHED,finished.status()); assertEquals(7,finished.stateVersion());
    for (int i=0;i<=7;i++) assertTrue(snapshots.find(finished.matchId(),i).isPresent());
    assertEquals("MATCH_NOT_ACTIVE",command(room.roomId(),ControlCommandType.CONTINUE,null).errorCode());
    assertNull(command(room.roomId(),ControlCommandType.RESET,null).errorCode());
    assertEquals(finished,matches.find(finished.matchId()).orElseThrow());
    Room reset=rooms.find(room.roomId()).orElseThrow(); assertNotEquals(finished.matchId(),reset.activeMatchId());
    assertEquals(MatchStatus.READY,matches.find(reset.activeMatchId()).orElseThrow().status());
  }
  @Test void stepPauseContinueAndReplayDoNotGrantExtraDecisions() throws Exception {
    Room room=fixture(); var id=UUID.randomUUID();
    var step=new ControlCommand(id,0,room.activeMatchId(),ControlCommandType.STEP,null);
    var receipt=scheduler.handleCommand(room.roomId(),step); Call first=runtime.next();
    assertEquals(receipt,scheduler.handleCommand(room.roomId(),step));
    assertEquals("COMMAND_ID_CONFLICT",scheduler.handleCommand(room.roomId(),new ControlCommand(id,0,room.activeMatchId(),ControlCommandType.START,null)).errorCode());
    assertEquals("CONTROL_BUSY",command(room.roomId(),ControlCommandType.STEP,null).errorCode());
    choose(first,0);
    assertEquals(ExecutionStatus.PAUSED,rooms.find(room.roomId()).orElseThrow().executionStatus());
    assertNull(command(room.roomId(),ControlCommandType.CONTINUE,null).errorCode()); Call second=runtime.next();
    assertNull(command(room.roomId(),ControlCommandType.PAUSE,null).errorCode());
    assertEquals(ExecutionStatus.PAUSING,rooms.find(room.roomId()).orElseThrow().executionStatus()); choose(second,1);
    assertEquals(2,matches.find(room.activeMatchId()).orElseThrow().stateVersion());
    assertEquals(ExecutionStatus.PAUSED,rooms.find(room.roomId()).orElseThrow().executionStatus());
    assertNull(command(room.roomId(),ControlCommandType.CONTINUE,null).errorCode());
    for (int move:new int[]{0,1,0,1,0}) choose(runtime.next(),move);
    assertEquals(MatchStatus.FINISHED,matches.find(room.activeMatchId()).orElseThrow().status());
    assertEquals(7,matches.find(room.activeMatchId()).orElseThrow().stateVersion());
  }
  @Test void cancelAndResetIsolateLateRuntimeResults() throws Exception {
    Room room=fixture(); command(room.roomId(),ControlCommandType.START,null); Call old=runtime.next();
    Room beforeCancel=rooms.find(room.roomId()).orElseThrow();
    var cancel=new ControlCommand(UUID.randomUUID(),beforeCancel.revision(),beforeCancel.activeMatchId(),ControlCommandType.CANCEL_DECISION,null);
    var cancelled=scheduler.handleCommand(room.roomId(),cancel); assertNull(cancelled.errorCode());
    assertEquals(DecisionStatus.CANCELLED,decisions.find(old.input().publicRequest().decisionId()).orElseThrow().status());
    assertNull(command(room.roomId(),ControlCommandType.CONTINUE,null).errorCode()); Call next=runtime.next();
    assertEquals(cancelled,scheduler.handleCommand(room.roomId(),cancel));
    assertEquals(DecisionStatus.RUNNING,decisions.find(next.input().publicRequest().decisionId()).orElseThrow().status());
    assertNull(command(room.roomId(),ControlCommandType.RESET,null).errorCode());
    UUID replacement=rooms.find(room.roomId()).orElseThrow().activeMatchId();
    choose(old,0); choose(next,1);
    assertEquals(0,matches.find(room.activeMatchId()).orElseThrow().stateVersion());
    assertEquals(0,matches.find(replacement).orElseThrow().stateVersion());
    assertEquals(MatchStatus.ABORTED,matches.find(room.activeMatchId()).orElseThrow().status());
    assertNull(command(room.roomId(),ControlCommandType.STEP,null).errorCode()); Call fresh=runtime.next(); choose(fresh,2);
    assertNull(command(room.roomId(),ControlCommandType.CONTINUE,null).errorCode());
    for (int move:new int[]{0,2,0,2,0,2}) choose(runtime.next(),move);
    assertEquals(MatchStatus.FINISHED,matches.find(replacement).orElseThrow().status());
    assertEquals(7,matches.find(replacement).orElseThrow().stateVersion());
    assertEquals(0,matches.find(room.activeMatchId()).orElseThrow().stateVersion());
  }
  @Test void startCompletesDrawWithOneSnapshotPerMove() throws Exception {
    Room room=fixture(); assertNull(command(room.roomId(),ControlCommandType.START,null).errorCode());
    int[] moves={3,1,3,3,0,2,1,1,4,4,1,6,3,3,2,0,4,4,4,6,3,1,0,2,1,0,2,6,6,2,4,2,5,5,6,6,5,5,5,5,0,0};
    long version=0; for (int move:moves) { Call call=runtime.next(); choose(call,move); assertSettlement(call,++version); }
    Match match=matches.find(room.activeMatchId()).orElseThrow();
    assertEquals(MatchStatus.FINISHED,match.status()); assertEquals(GameOutcome.DRAW,match.result().status());
    assertEquals(42,match.stateVersion());
    for (int i=0;i<=42;i++) assertTrue(snapshots.find(match.matchId(),i).isPresent());
    assertEquals(ExecutionStatus.PAUSED,rooms.find(room.roomId()).orElseThrow().executionStatus());
  }
  @Test void modesAndDeterministicRejectionsAreStored() throws Exception {
    Room room=fixture();
    var rejected=new ControlCommand(UUID.randomUUID(),0,room.activeMatchId(),ControlCommandType.CONTINUE,null);
    var first=scheduler.handleCommand(room.roomId(),rejected); assertEquals("MATCH_NOT_ACTIVE",first.errorCode());
    assertNull(command(room.roomId(),ControlCommandType.SET_MODE,ExecutionMode.AUTO).errorCode());
    assertEquals(first,scheduler.handleCommand(room.roomId(),rejected));
    assertEquals(ExecutionStatus.PAUSED,rooms.find(room.roomId()).orElseThrow().executionStatus());
    assertNull(command(room.roomId(),ControlCommandType.STEP,null).errorCode()); Call call=runtime.next();
    assertNull(command(room.roomId(),ControlCommandType.SET_MODE,ExecutionMode.AUTO).errorCode());
    assertEquals(ExecutionStatus.RUNNING,rooms.find(room.roomId()).orElseThrow().executionStatus());
    assertNull(command(room.roomId(),ControlCommandType.SET_MODE,ExecutionMode.STEP).errorCode());
    assertEquals(ExecutionStatus.PAUSING,rooms.find(room.roomId()).orElseThrow().executionStatus());
    assertNull(command(room.roomId(),ControlCommandType.SET_MODE,ExecutionMode.AUTO).errorCode());
    assertEquals(ExecutionStatus.PAUSING,rooms.find(room.roomId()).orElseThrow().executionStatus());
    choose(call,0); assertEquals(ExecutionStatus.PAUSED,rooms.find(room.roomId()).orElseThrow().executionStatus());
    assertEquals("NO_ACTIVE_DECISION",command(room.roomId(),ControlCommandType.CANCEL_DECISION,null).errorCode());
    var stale=new ControlCommand(UUID.randomUUID(),0,room.activeMatchId(),ControlCommandType.CONTINUE,null);
    assertEquals("ROOM_REVISION_CONFLICT",scheduler.handleCommand(room.roomId(),stale).errorCode());
  }
  @Test void resetWriteFailureRollsBackAllFacts() {
    Room room=fixture(); Match before=matches.find(room.activeMatchId()).orElseThrow();
    var cmd=new ControlCommand(UUID.randomUUID(),room.revision(),room.activeMatchId(),ControlCommandType.RESET,null);
    for (String fault:List.of("factory.create","turn.replace","match.replace","match.insert","turn.insert","snapshot.append","room.replace","roomEvent.append","commandReceipt.save")) {
      faults.target=fault;
      try { assertThrows(CompletionException.class,()->scheduler.handleCommand(room.roomId(),cmd)); }
      finally { faults.target=null; }
      assertEquals(room,rooms.find(room.roomId()).orElseThrow()); assertEquals(before,matches.find(before.matchId()).orElseThrow());
      assertEquals(TurnStatus.OPEN,turns.find(before.currentTurnId()).orElseThrow().status());
      assertTrue(events.readAfter(room.roomId(),0,100).isEmpty());
    }
    assertNull(scheduler.handleCommand(room.roomId(),cmd).errorCode());
  }
  @Test void earlyDeadlineReschedulesAndExactDeadlineExpiresThenContinue() throws Exception {
    var mutable=(DecisionExecutionIntegrationTest.MutableClock)clock; Room room=fixture();
    try {
      command(room.roomId(),ControlCommandType.START,null); Call call=runtime.next();
      UUID id=call.input().publicRequest().decisionId(); Instant deadline=call.input().publicRequest().deadlineAt();
      mutable.set(deadline.minusMillis(1)); deadlines.firePending();
      command(room.roomId(),ControlCommandType.PAUSE,null);
      assertEquals(DecisionStatus.RUNNING,decisions.find(id).orElseThrow().status());
      mutable.set(deadline); deadlines.firePending();
      command(room.roomId(),ControlCommandType.PAUSE,null);
      assertEquals(DecisionStatus.EXPIRED,decisions.find(id).orElseThrow().status());
      assertEquals(0,matches.find(room.activeMatchId()).orElseThrow().stateVersion());
      assertNull(command(room.roomId(),ControlCommandType.CONTINUE,null).errorCode());
      Call retry=runtime.next(); command(room.roomId(),ControlCommandType.SET_MODE,ExecutionMode.STEP); choose(call,0); choose(retry,1);
      assertEquals(1,matches.find(room.activeMatchId()).orElseThrow().stateVersion());
    } finally { mutable.set(DecisionExecutionIntegrationTest.MutableClock.INITIAL); }
  }
  @Test void runtimeFailurePausesAndContinueStartsNewDecision() throws Exception {
    Room room=fixture(); command(room.roomId(),ControlCommandType.START,null); Call failed=runtime.next();
    failed.future().complete(new RuntimeOutcome.Failed(new RuntimeError("MODEL_UNAVAILABLE","offline"))); failed.finished().get(5,TimeUnit.SECONDS);
    assertEquals(DecisionStatus.FAILED,decisions.find(failed.input().publicRequest().decisionId()).orElseThrow().status());
    assertEquals(ExecutionStatus.PAUSED,rooms.find(room.roomId()).orElseThrow().executionStatus());
    assertEquals(0,matches.find(room.activeMatchId()).orElseThrow().stateVersion());
    assertNull(command(room.roomId(),ControlCommandType.CONTINUE,null).errorCode()); Call retry=runtime.next();
    assertNotEquals(failed.input().publicRequest().decisionId(),retry.input().publicRequest().decisionId());
    command(room.roomId(),ControlCommandType.SET_MODE,ExecutionMode.STEP); choose(retry,0);
    assertEquals(1,matches.find(room.activeMatchId()).orElseThrow().stateVersion());
  }
  @Test void cancelBeforeSubmissionAndSubmissionBeforeCancelHaveOneAuthoritativeOutcome() throws Exception {
    Room cancelledRoom=fixture(); command(cancelledRoom.roomId(),ControlCommandType.STEP,null); Call cancelled=runtime.next();
    assertNull(command(cancelledRoom.roomId(),ControlCommandType.CANCEL_DECISION,null).errorCode());
    choose(cancelled,0);
    assertEquals(DecisionStatus.CANCELLED,decisions.find(cancelled.input().publicRequest().decisionId()).orElseThrow().status());
    assertTrue(settlements.findByTurnId(cancelled.input().publicRequest().turnId()).isEmpty());
    assertEquals(0,matches.find(cancelledRoom.activeMatchId()).orElseThrow().stateVersion());

    Room submittedRoom=fixture(); command(submittedRoom.roomId(),ControlCommandType.STEP,null); Call submitted=runtime.next();
    choose(submitted,0); assertSettlement(submitted,1);
    assertEquals("NO_ACTIVE_DECISION",command(submittedRoom.roomId(),ControlCommandType.CANCEL_DECISION,null).errorCode());
    assertSettlement(submitted,1); assertEquals(1,matches.find(submittedRoom.activeMatchId()).orElseThrow().stateVersion());
  }
  @Test void oldDeadlineAndLateCallbackDoNotCancelNewHandle() throws Exception {
    var mutable=(DecisionExecutionIntegrationTest.MutableClock)clock; Room room=fixture();
    try {
      command(room.roomId(),ControlCommandType.START,null); Call old=runtime.next();
      Instant oldDeadline=old.input().publicRequest().deadlineAt();
      command(room.roomId(),ControlCommandType.RESET,null);
      mutable.set(DecisionExecutionIntegrationTest.MutableClock.INITIAL.plusSeconds(1));
      command(room.roomId(),ControlCommandType.STEP,null); Call fresh=runtime.next();
      mutable.set(oldDeadline); deadlines.firePending();
      // A command in the same lane is a barrier for the queued deadline callbacks.
      command(room.roomId(),ControlCommandType.SET_MODE,ExecutionMode.STEP);
      assertEquals(DecisionStatus.RUNNING,decisions.find(fresh.input().publicRequest().decisionId()).orElseThrow().status());
      choose(old,0); assertEquals(DecisionStatus.RUNNING,decisions.find(fresh.input().publicRequest().decisionId()).orElseThrow().status());
      choose(fresh,1); assertSettlement(fresh,1);
    } finally { mutable.set(DecisionExecutionIntegrationTest.MutableClock.INITIAL); }
  }
  @Test void activeResetWriteFailuresRollBackDecisionAndDoNotSignalCancellation() throws Exception {
    Room room=fixture(); command(room.roomId(),ControlCommandType.START,null); Call call=runtime.next();
    Room before=rooms.find(room.roomId()).orElseThrow(); Match match=matches.find(room.activeMatchId()).orElseThrow();
    UUID decisionId=call.input().publicRequest().decisionId(); var decision=decisions.find(decisionId).orElseThrow();
    var cmd=new ControlCommand(UUID.randomUUID(),before.revision(),before.activeMatchId(),ControlCommandType.RESET,null);
    for (String fault:List.of("decision.replace","record.replace","turn.replace","room.replace","roomEvent.append","factory.create","snapshot.append","commandReceipt.save")) {
      faults.target=fault;
      try { assertThrows(CompletionException.class,()->scheduler.handleCommand(room.roomId(),cmd)); }
      finally { faults.target=null; }
      assertEquals(before,rooms.find(room.roomId()).orElseThrow()); assertEquals(match,matches.find(match.matchId()).orElseThrow());
      assertEquals(decision,decisions.find(decisionId).orElseThrow());
      assertEquals(TurnStatus.OPEN,turns.find(decision.turnId()).orElseThrow().status());
      assertFalse(call.future().isDone());
    }
    // Successful submission proves failed resets did not signal the local handle.
    command(room.roomId(),ControlCommandType.SET_MODE,ExecutionMode.STEP); choose(call,0); assertSettlement(call,1);
  }
  @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"decision.insert","inputs.save","record.insert","room.replace","roomEvent.append"})
  void failedDecisionStartPausesAndCanBeContinued(String fault) throws Exception {
    Room room=fixture(); faults.failureEvent=new CountDownLatch(1); faults.skip=fault.startsWith("room")?1:0; faults.once=true; faults.target=fault;
    try {
      assertNull(command(room.roomId(),ControlCommandType.START,null).errorCode());
      assertTrue(faults.failureEvent.await(5,TimeUnit.SECONDS));
      command(room.roomId(),ControlCommandType.PAUSE,null);
      Room paused=rooms.find(room.roomId()).orElseThrow(); assertEquals(ExecutionStatus.PAUSED,paused.executionStatus());
      assertTrue(events.readAfter(room.roomId(),0,100).stream().anyMatch(e->"DECISION_START_FAILED".equals(e.payload().get("errorCode"))));
      assertTrue(decisions.findByTurn(matches.find(room.activeMatchId()).orElseThrow().currentTurnId()).isEmpty());
    } finally { faults.target=null; faults.failureEvent=null; faults.skip=0; faults.once=false; }
    assertNull(command(room.roomId(),ControlCommandType.CONTINUE,null).errorCode()); Call retry=runtime.next();
    command(room.roomId(),ControlCommandType.SET_MODE,ExecutionMode.STEP); choose(retry,0); assertSettlement(retry,1);
  }
  @Test void pendingCandidatePausesThenContinuesWithSameDecisionAndAction() throws Exception {
    Room room=fixture(); command(room.roomId(),ControlCommandType.START,null); Call call=runtime.next();
    UUID id=call.input().publicRequest().decisionId(); faults.failureEvent=new CountDownLatch(1); faults.target="record.replace";
    try {
      choose(call,0); assertTrue(faults.failureEvent.await(5,TimeUnit.SECONDS));
      command(room.roomId(),ControlCommandType.PAUSE,null);
      assertEquals(ExecutionStatus.PAUSED,rooms.find(room.roomId()).orElseThrow().executionStatus());
      assertEquals(DecisionStatus.RUNNING,decisions.find(id).orElseThrow().status());
      assertTrue(events.readAfter(room.roomId(),0,100).stream().anyMatch(e->"DECISION_OUTPUT_PERSISTENCE_FAILED".equals(e.payload().get("errorCode"))));
    } finally { faults.target=null; faults.failureEvent=null; }
    var pending=lifecycle.pendingOutputs(); var candidate=(PendingDecisionOutputStore.Candidate)pending.acquire(id).orElseThrow();
    UUID actionId=candidate.envelope().actionId(); pending.release(id,false);
    runtime.recoveryDone=new CountDownLatch(1);
    try {
      Room before=rooms.find(room.roomId()).orElseThrow(); var cmd=new ControlCommand(UUID.randomUUID(),before.revision(),before.activeMatchId(),ControlCommandType.CONTINUE,null);
      var result=scheduler.handleCommand(room.roomId(),cmd); assertNull(result.errorCode());
      assertEquals(result,scheduler.handleCommand(room.roomId(),cmd));
      assertTrue(runtime.recoveryDone.await(5,TimeUnit.SECONDS));
      assertEquals(actionId,records.find(id).orElseThrow().submissionResult().actionId()); assertSettlement(call,1);
      assertFalse(pending.contains(id)); assertEquals(1,runtime.invocations.get(id).get());
      assertNull(cleanupCommand(room.roomId(),ControlCommandType.PAUSE).errorCode());
      if (rooms.find(room.roomId()).orElseThrow().executionStatus()==ExecutionStatus.PAUSING) {
        Call next=runtime.next(); assertNull(cleanupCommand(room.roomId(),ControlCommandType.CANCEL_DECISION).errorCode()); choose(next,0);
      }
    } finally { runtime.recoveryDone=null; }
  }
  @Test void pendingCandidateKeepsOriginalDeadlineAndExpiredDecisionIsNotRecovered() throws Exception {
    var mutable=(DecisionExecutionIntegrationTest.MutableClock)clock; Room room=fixture();
    try {
      command(room.roomId(),ControlCommandType.START,null); Call old=runtime.next(); UUID id=old.input().publicRequest().decisionId();
      faults.failureEvent=new CountDownLatch(1); faults.target="record.replace";
      choose(old,0); assertTrue(faults.failureEvent.await(5,TimeUnit.SECONDS)); command(room.roomId(),ControlCommandType.PAUSE,null);
      faults.target=null; faults.failureEvent=null;
      mutable.set(old.input().publicRequest().deadlineAt()); deadlines.firePending(); command(room.roomId(),ControlCommandType.PAUSE,null);
      assertEquals(DecisionStatus.EXPIRED,decisions.find(id).orElseThrow().status());
      assertEquals(0,matches.find(room.activeMatchId()).orElseThrow().stateVersion());
      command(room.roomId(),ControlCommandType.CONTINUE,null); Call retry=runtime.next();
      assertNotEquals(id,retry.input().publicRequest().decisionId());
      command(room.roomId(),ControlCommandType.SET_MODE,ExecutionMode.STEP); choose(retry,1); assertSettlement(retry,1);
      assertEquals(DecisionStatus.EXPIRED,decisions.find(id).orElseThrow().status());
    } finally { faults.target=null; faults.failureEvent=null; mutable.set(DecisionExecutionIntegrationTest.MutableClock.INITIAL); }
  }
  @Test void deadlineWriteFailureRollsBackAndRetriesWithoutAnotherRuntimeCall() throws Exception {
    var mutable=(DecisionExecutionIntegrationTest.MutableClock)clock; Room room=fixture(); deadlines.scheduled.clear();
    try {
      command(room.roomId(),ControlCommandType.START,null); Call call=runtime.next(); UUID id=call.input().publicRequest().decisionId();
      assertNull(command(room.roomId(),ControlCommandType.PAUSE,null).errorCode());
      Room before=rooms.find(room.roomId()).orElseThrow(); int eventCount=events.readAfter(room.roomId(),0,100).size();
      mutable.set(call.input().publicRequest().deadlineAt()); faults.once=true; faults.target="roomEvent.append";
      deadlines.firePending(); assertNull(cleanupCommand(room.roomId(),ControlCommandType.PAUSE).errorCode());
      assertNull(faults.target); assertEquals(before,rooms.find(room.roomId()).orElseThrow());
      assertEquals(DecisionStatus.RUNNING,decisions.find(id).orElseThrow().status());
      assertEquals(eventCount,events.readAfter(room.roomId(),0,100).size());
      assertEquals(1,deadlines.scheduled.size()); assertTrue(deadlines.scheduled.peek().delayMillis()>0);
      deadlines.firePending(); assertNull(cleanupCommand(room.roomId(),ControlCommandType.PAUSE).errorCode());
      assertEquals(DecisionStatus.EXPIRED,decisions.find(id).orElseThrow().status());
      Room paused=rooms.find(room.roomId()).orElseThrow(); assertEquals(ExecutionStatus.PAUSED,paused.executionStatus());
      assertEquals(before.revision()+1,paused.revision()); assertEquals(before.eventSeq()+1,paused.eventSeq());
      assertEquals(1,runtime.invocations.get(id).get()); assertEquals(0,matches.find(room.activeMatchId()).orElseThrow().stateVersion());
      choose(call,0); assertEquals(DecisionStatus.EXPIRED,decisions.find(id).orElseThrow().status());
    } finally { faults.target=null; faults.once=false; mutable.set(DecisionExecutionIntegrationTest.MutableClock.INITIAL); }
  }
  @Test void repeatedDeadlineFailuresKeepOneDelayedRetry() throws Exception {
    var mutable=(DecisionExecutionIntegrationTest.MutableClock)clock; Room room=fixture(); deadlines.scheduled.clear();
    try {
      command(room.roomId(),ControlCommandType.START,null); Call call=runtime.next(); UUID id=call.input().publicRequest().decisionId();
      command(room.roomId(),ControlCommandType.PAUSE,null); mutable.set(call.input().publicRequest().deadlineAt());
      faults.target="roomEvent.append";
      for (int i=0;i<4;i++) {
        deadlines.firePending(); assertNull(cleanupCommand(room.roomId(),ControlCommandType.PAUSE).errorCode());
        assertEquals(DecisionStatus.RUNNING,decisions.find(id).orElseThrow().status());
        assertEquals(1,deadlines.scheduled.size()); assertTrue(deadlines.scheduled.peek().delayMillis()>=250);
      }
      faults.target=null; deadlines.firePending(); assertNull(cleanupCommand(room.roomId(),ControlCommandType.PAUSE).errorCode());
      assertEquals(DecisionStatus.EXPIRED,decisions.find(id).orElseThrow().status());
      assertEquals(1,runtime.invocations.get(id).get()); choose(call,0);
    } finally { faults.target=null; mutable.set(DecisionExecutionIntegrationTest.MutableClock.INITIAL); }
  }
  @Test void rejectedDeadlineDispatchRetriesUntilDecisionExpires() throws Exception {
    var mutable=(DecisionExecutionIntegrationTest.MutableClock)clock; Room room=fixture(); deadlines.scheduled.clear();
    try {
      assertNull(command(room.roomId(),ControlCommandType.START,null).errorCode()); Call call=runtime.next();
      UUID id=call.input().publicRequest().decisionId(); assertNull(cleanupCommand(room.roomId(),ControlCommandType.PAUSE).errorCode());
      controlExecutor.awaitIdle(); Room before=rooms.find(room.roomId()).orElseThrow(); mutable.set(call.input().publicRequest().deadlineAt());
      for (int i=0;i<3;i++) {
        controlExecutor.rejectNext.set(true); deadlines.firePending();
        assertFalse(controlExecutor.rejectNext.get()); assertEquals(1,deadlines.scheduled.size());
        assertTrue(deadlines.scheduled.peek().delayMillis()>=250);
        assertEquals(before,rooms.find(room.roomId()).orElseThrow());
        assertEquals(DecisionStatus.RUNNING,decisions.find(id).orElseThrow().status());
      }
      deadlines.firePending(); assertNull(cleanupCommand(room.roomId(),ControlCommandType.PAUSE).errorCode());
      Room after=rooms.find(room.roomId()).orElseThrow(); assertEquals(ExecutionStatus.PAUSED,after.executionStatus());
      assertEquals(before.revision()+1,after.revision()); assertEquals(before.eventSeq()+1,after.eventSeq());
      assertEquals(DecisionStatus.EXPIRED,decisions.find(id).orElseThrow().status());
      assertEquals(1,runtime.invocations.get(id).get()); choose(call,0);
      assertEquals(DecisionStatus.EXPIRED,decisions.find(id).orElseThrow().status());
    } finally { controlExecutor.rejectNext.set(false); mutable.set(DecisionExecutionIntegrationTest.MutableClock.INITIAL); }
  }
  @Test void rejectedCompletionDispatchResumesAutoWithoutRepeatingDecision() throws Exception {
    Room room=fixture(); deadlines.scheduled.clear();
    assertNull(command(room.roomId(),ControlCommandType.START,null).errorCode()); Call first=runtime.next();
    deadlines.scheduled.clear(); controlExecutor.awaitIdle(); controlExecutor.armRejection(); choose(first,0); assertTrue(controlExecutor.rejection.await(5,TimeUnit.SECONDS));
    deadlines.awaitScheduled(); assertFalse(controlExecutor.rejectNext.get()); assertSettlement(first,1);
    assertEquals(1,deadlines.scheduled.size()); assertTrue(deadlines.scheduled.peek().delayMillis()>=250);
    deadlines.firePending(); Call next=runtime.next();
    assertNotEquals(first.input().publicRequest().decisionId(),next.input().publicRequest().decisionId());
    assertNull(command(room.roomId(),ControlCommandType.SET_MODE,ExecutionMode.STEP).errorCode());
    choose(next,1); assertSettlement(next,2);
    assertEquals(1,runtime.invocations.get(first.input().publicRequest().decisionId()).get());
  }
  @Test void rejectedCompletionOfCancelledDecisionCannotAdvanceNewMatch() throws Exception {
    Room room=fixture(); deadlines.scheduled.clear(); assertNull(command(room.roomId(),ControlCommandType.START,null).errorCode());
    Call old=runtime.next(); assertNull(cleanupCommand(room.roomId(),ControlCommandType.RESET).errorCode());
    deadlines.scheduled.clear(); controlExecutor.awaitIdle(); controlExecutor.armRejection(); choose(old,0); assertTrue(controlExecutor.rejection.await(5,TimeUnit.SECONDS));
    deadlines.awaitScheduled(); assertFalse(controlExecutor.rejectNext.get()); assertEquals(1,deadlines.scheduled.size());
    assertNull(cleanupCommand(room.roomId(),ControlCommandType.STEP).errorCode()); Call fresh=runtime.next();
    deadlines.firePending(); assertNull(cleanupCommand(room.roomId(),ControlCommandType.PAUSE).errorCode());
    assertEquals(DecisionStatus.RUNNING,decisions.find(fresh.input().publicRequest().decisionId()).orElseThrow().status());
    choose(fresh,1); assertSettlement(fresh,1);
    assertEquals(1,runtime.invocations.get(old.input().publicRequest().decisionId()).get());
  }
  @Test void rejectedCompletionAfterTerminalWinCannotScheduleAnotherDecision() throws Exception {
    Room room=fixture(); assertNull(command(room.roomId(),ControlCommandType.START,null).errorCode());
    for (int move:new int[]{0,1,0,1,0,1}) choose(runtime.next(),move);
    Call last=runtime.next(); deadlines.scheduled.clear(); controlExecutor.awaitIdle(); controlExecutor.armRejection();
    choose(last,0); assertTrue(controlExecutor.rejection.await(5,TimeUnit.SECONDS)); deadlines.awaitScheduled();
    Room finished=rooms.find(room.roomId()).orElseThrow(); assertEquals(MatchStatus.FINISHED,matches.find(room.activeMatchId()).orElseThrow().status());
    deadlines.firePending(); assertEquals("MATCH_NOT_ACTIVE",cleanupCommand(room.roomId(),ControlCommandType.PAUSE).errorCode());
    assertEquals(finished,rooms.find(room.roomId()).orElseThrow()); assertTrue(runtime.calls.isEmpty()); assertSettlement(last,7);
  }
  @Test void rejectedTerminalCleanupDispatchRetriesAfterClaimRelease() throws Exception {
    Room room=fixture(); deadlines.scheduled.clear(); Call old=createPending(room,false); deadlines.scheduled.clear();
    UUID id=old.input().publicRequest().decisionId(); var pending=lifecycle.pendingOutputs(); assertTrue(pending.acquire(id).isPresent());
    assertNull(cleanupCommand(room.roomId(),ControlCommandType.RESET).errorCode()); assertTrue(pending.contains(id));
    assertEquals(1,deadlines.scheduled.size()); pending.release(id,false); controlExecutor.awaitIdle(); controlExecutor.rejectNext.set(true);
    deadlines.firePending(); assertFalse(controlExecutor.rejectNext.get());
    assertEquals(1,deadlines.scheduled.size()); assertTrue(deadlines.scheduled.peek().delayMillis()>=250); assertTrue(pending.contains(id));
    deadlines.firePending(); assertNull(command(room.roomId(),ControlCommandType.SET_MODE,ExecutionMode.STEP).errorCode());
    assertFalse(pending.contains(id)); assertTrue(records.find(id).isPresent());
    assertNull(cleanupCommand(room.roomId(),ControlCommandType.STEP).errorCode()); Call fresh=runtime.next(); choose(fresh,1); assertSettlement(fresh,1);
  }
  @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"reset","cancel"})
  void rejectedOldDeadlineDispatchCannotAffectReplacement(String end) throws Exception {
    var mutable=(DecisionExecutionIntegrationTest.MutableClock)clock; Room room=fixture(); deadlines.scheduled.clear();
    try {
      assertNull(command(room.roomId(),ControlCommandType.START,null).errorCode()); Call old=runtime.next();
      assertNull(cleanupCommand(room.roomId(),ControlCommandType.PAUSE).errorCode());
      controlExecutor.awaitIdle(); mutable.set(old.input().publicRequest().deadlineAt()); controlExecutor.rejectNext.set(true); deadlines.firePending();
      assertEquals(1,deadlines.scheduled.size());
      assertNull(cleanupCommand(room.roomId(),end.equals("reset")?ControlCommandType.RESET:ControlCommandType.CANCEL_DECISION).errorCode());
      mutable.set(DecisionExecutionIntegrationTest.MutableClock.INITIAL.plusSeconds(1));
      assertNull(cleanupCommand(room.roomId(),end.equals("reset")?ControlCommandType.STEP:ControlCommandType.CONTINUE).errorCode());
      Call fresh=runtime.next(); mutable.set(old.input().publicRequest().deadlineAt()); deadlines.firePending();
      assertNull(cleanupCommand(room.roomId(),ControlCommandType.PAUSE).errorCode());
      assertEquals(DecisionStatus.RUNNING,decisions.find(fresh.input().publicRequest().decisionId()).orElseThrow().status());
      choose(old,0); choose(fresh,1); assertSettlement(fresh,1);
      assertEquals(1,runtime.invocations.get(old.input().publicRequest().decisionId()).get());
    } finally { controlExecutor.rejectNext.set(false); mutable.set(DecisionExecutionIntegrationTest.MutableClock.INITIAL); }
  }
  @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"reset","cancel","completed"})
  void oldDeadlineRetryCannotChangeTerminalOrReplacementDecision(String end) throws Exception {
    var mutable=(DecisionExecutionIntegrationTest.MutableClock)clock; Room room=fixture(); deadlines.scheduled.clear();
    try {
      command(room.roomId(),ControlCommandType.START,null); Call old=runtime.next(); UUID id=old.input().publicRequest().decisionId();
      command(room.roomId(),ControlCommandType.PAUSE,null); mutable.set(old.input().publicRequest().deadlineAt());
      faults.once=true; faults.target="roomEvent.append"; deadlines.firePending(); assertNull(cleanupCommand(room.roomId(),ControlCommandType.PAUSE).errorCode());
      assertEquals(1,deadlines.scheduled.size());
      if (end.equals("completed")) {
        mutable.set(old.input().publicRequest().deadlineAt().minusMillis(1)); choose(old,0); assertSettlement(old,1);
        Room finished=rooms.find(room.roomId()).orElseThrow(); mutable.set(old.input().publicRequest().deadlineAt());
        deadlines.firePending(); assertNull(cleanupCommand(room.roomId(),ControlCommandType.PAUSE).errorCode());
        assertEquals(finished,rooms.find(room.roomId()).orElseThrow()); assertEquals(DecisionStatus.COMPLETED,decisions.find(id).orElseThrow().status());
      } else {
        if (end.equals("reset")) command(room.roomId(),ControlCommandType.RESET,null);
        else command(room.roomId(),ControlCommandType.CANCEL_DECISION,null);
        mutable.set(DecisionExecutionIntegrationTest.MutableClock.INITIAL.plusSeconds(1));
        command(room.roomId(),end.equals("reset")?ControlCommandType.STEP:ControlCommandType.CONTINUE,null);
        Call fresh=runtime.next(); Room before=rooms.find(room.roomId()).orElseThrow();
        mutable.set(old.input().publicRequest().deadlineAt()); deadlines.firePending();
        assertNull(cleanupCommand(room.roomId(),ControlCommandType.PAUSE).errorCode());
        assertEquals(DecisionStatus.RUNNING,decisions.find(fresh.input().publicRequest().decisionId()).orElseThrow().status());
        assertEquals(before.activeMatchId(),rooms.find(room.roomId()).orElseThrow().activeMatchId());
        choose(old,0); choose(fresh,1); assertSettlement(fresh,1);
      }
    } finally { faults.target=null; faults.once=false; mutable.set(DecisionExecutionIntegrationTest.MutableClock.INITIAL); }
  }
  @Test void pendingOutputStillExpiresAfterFailedDeadlineWrite() throws Exception {
    var mutable=(DecisionExecutionIntegrationTest.MutableClock)clock; Room room=fixture(); deadlines.scheduled.clear();
    try {
      command(room.roomId(),ControlCommandType.START,null); Call call=runtime.next(); UUID id=call.input().publicRequest().decisionId();
      faults.failureEvent=new CountDownLatch(1); faults.target="record.replace"; choose(call,0);
      assertTrue(faults.failureEvent.await(5,TimeUnit.SECONDS)); command(room.roomId(),ControlCommandType.PAUSE,null);
      faults.target=null; faults.failureEvent=null; Room paused=rooms.find(room.roomId()).orElseThrow();
      assertEquals(ExecutionStatus.PAUSED,paused.executionStatus()); assertTrue(lifecycle.pendingOutputs().contains(id));
      mutable.set(call.input().publicRequest().deadlineAt()); faults.once=true; faults.target="roomEvent.append";
      deadlines.firePending(); assertNull(cleanupCommand(room.roomId(),ControlCommandType.PAUSE).errorCode());
      assertEquals(DecisionStatus.RUNNING,decisions.find(id).orElseThrow().status()); assertEquals(paused,rooms.find(room.roomId()).orElseThrow());
      assertEquals(1,deadlines.scheduled.size()); deadlines.firePending(); assertNull(cleanupCommand(room.roomId(),ControlCommandType.PAUSE).errorCode());
      assertEquals(DecisionStatus.EXPIRED,decisions.find(id).orElseThrow().status());
      assertEquals(ExecutionStatus.PAUSED,rooms.find(room.roomId()).orElseThrow().executionStatus());
      assertEquals(1,runtime.invocations.get(id).get());
    } finally { faults.target=null; faults.once=false; faults.failureEvent=null; mutable.set(DecisionExecutionIntegrationTest.MutableClock.INITIAL); }
  }
  @Test void oldPendingFailureCannotPauseNewMatch() throws Exception {
    Room room=fixture(); command(room.roomId(),ControlCommandType.START,null); Call old=runtime.next(); UUID id=old.input().publicRequest().decisionId();
    faults.failureEvent=new CountDownLatch(1); faults.target="record.replace";
    try { choose(old,0); assertTrue(faults.failureEvent.await(5,TimeUnit.SECONDS)); command(room.roomId(),ControlCommandType.PAUSE,null); }
    finally { faults.target=null; faults.failureEvent=null; }
    var oldStarted=inputSnapshots.find(id).orElseThrow().started(); command(room.roomId(),ControlCommandType.RESET,null);
    command(room.roomId(),ControlCommandType.STEP,null); Call fresh=runtime.next();
    assertFalse(tickService.pausePending(oldStarted));
    assertEquals(ExecutionStatus.RUNNING,rooms.find(room.roomId()).orElseThrow().executionStatus());
    assertEquals(DecisionStatus.RUNNING,decisions.find(fresh.input().publicRequest().decisionId()).orElseThrow().status());
    choose(fresh,1); assertSettlement(fresh,1);
  }
  private Call createPending(Room room,boolean failure) throws Exception {
    assertNull(command(room.roomId(),ControlCommandType.START,null).errorCode()); Call call=runtime.next();
    faults.failureEvent=new CountDownLatch(1); faults.target="record.replace";
    try {
      if (failure) { call.future().complete(new RuntimeOutcome.Failed(new RuntimeError("MODEL_UNAVAILABLE","offline"))); call.finished().get(5,TimeUnit.SECONDS); }
      else choose(call,0);
      assertTrue(faults.failureEvent.await(5,TimeUnit.SECONDS)); assertNull(cleanupCommand(room.roomId(),ControlCommandType.PAUSE).errorCode());
      assertTrue(lifecycle.pendingOutputs().contains(call.input().publicRequest().decisionId()));
    } finally { faults.target=null; faults.failureEvent=null; }
    return call;
  }
  @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"continue","cancel","reset","pause"})
  void stepModeKeepsPendingDecisionPausedAndControlsRemainAvailable(String action) throws Exception {
    Room room=fixture(); Call old=createPending(room,false); UUID id=old.input().publicRequest().decisionId();
    var pending=lifecycle.pendingOutputs();
    var candidate=(PendingDecisionOutputStore.Candidate)pending.acquire(id).orElseThrow(); UUID actionId=candidate.envelope().actionId(); pending.release(id,false);
    Room before=rooms.find(room.roomId()).orElseThrow(); assertEquals(ExecutionStatus.PAUSED,before.executionStatus());
    var set=new ControlCommand(UUID.randomUUID(),before.revision(),before.activeMatchId(),ControlCommandType.SET_MODE,ExecutionMode.STEP);
    var result=scheduler.handleCommand(room.roomId(),set); assertNull(result.errorCode()); assertEquals(ExecutionStatus.PAUSED,result.executionStatus());
    assertEquals(ExecutionMode.STEP,result.executionMode()); assertEquals(before.revision()+1,result.roomRevision());
    assertEquals(result,scheduler.handleCommand(room.roomId(),set));
    Room stepRoom=rooms.find(room.roomId()).orElseThrow(); assertNull(command(room.roomId(),ControlCommandType.SET_MODE,ExecutionMode.STEP).errorCode());
    assertEquals(stepRoom,rooms.find(room.roomId()).orElseThrow());
    assertEquals("CONTROL_BUSY",cleanupCommand(room.roomId(),ControlCommandType.STEP).errorCode());
    assertTrue(pending.contains(id)); assertEquals(DecisionStatus.RUNNING,decisions.find(id).orElseThrow().status());
    if (action.equals("pause")) {
      Room unchanged=rooms.find(room.roomId()).orElseThrow(); assertNull(cleanupCommand(room.roomId(),ControlCommandType.PAUSE).errorCode());
      assertEquals(unchanged,rooms.find(room.roomId()).orElseThrow());
      assertNull(cleanupCommand(room.roomId(),ControlCommandType.CANCEL_DECISION).errorCode());
    } else if (action.equals("cancel") || action.equals("reset")) {
      assertNull(cleanupCommand(room.roomId(),action.equals("reset")?ControlCommandType.RESET:ControlCommandType.CANCEL_DECISION).errorCode());
      assertEquals(DecisionStatus.CANCELLED,decisions.find(id).orElseThrow().status()); assertFalse(pending.contains(id));
    } else {
      runtime.recoveryDone=new CountDownLatch(1);
      try {
        assertNull(cleanupCommand(room.roomId(),ControlCommandType.CONTINUE).errorCode());
        assertTrue(runtime.recoveryDone.await(5,TimeUnit.SECONDS)); assertSettlement(old,1);
        assertEquals(actionId,records.find(id).orElseThrow().submissionResult().actionId()); assertEquals(1,runtime.invocations.get(id).get());
        assertNull(cleanupCommand(room.roomId(),ControlCommandType.PAUSE).errorCode());
        if (rooms.find(room.roomId()).orElseThrow().executionStatus()==ExecutionStatus.PAUSING) {
          Call next=runtime.next(); assertNull(cleanupCommand(room.roomId(),ControlCommandType.CANCEL_DECISION).errorCode()); choose(next,0);
        }
      } finally { runtime.recoveryDone=null; }
    }
  }
  static Stream<Arguments> terminalPendingCases() {
    return Stream.of(Arguments.of(false,"cancel"),Arguments.of(false,"reset"),Arguments.of(false,"expire"),
        Arguments.of(true,"cancel"),Arguments.of(true,"reset"),Arguments.of(true,"expire"));
  }
  @ParameterizedTest @MethodSource("terminalPendingCases")
  void terminalDecisionClearsPendingAndNewDecisionCompletes(boolean failure,String end) throws Exception {
    var mutable=(DecisionExecutionIntegrationTest.MutableClock)clock; Room room=fixture(); deadlines.scheduled.clear();
    try {
      Call old=createPending(room,failure); UUID id=old.input().publicRequest().decisionId();
      if (end.equals("expire")) {
        mutable.set(old.input().publicRequest().deadlineAt()); deadlines.firePending();
        assertNull(cleanupCommand(room.roomId(),ControlCommandType.PAUSE).errorCode());
      } else assertNull(cleanupCommand(room.roomId(),end.equals("reset")?ControlCommandType.RESET:ControlCommandType.CANCEL_DECISION).errorCode());
      assertFalse(lifecycle.pendingOutputs().contains(id));
      assertEquals(end.equals("expire")?DecisionStatus.EXPIRED:DecisionStatus.CANCELLED,decisions.find(id).orElseThrow().status());
      assertTrue(records.find(id).isPresent());
      assertNull(cleanupCommand(room.roomId(),end.equals("reset")?ControlCommandType.STEP:ControlCommandType.CONTINUE).errorCode());
      Call fresh=runtime.next(); assertNotEquals(id,fresh.input().publicRequest().decisionId());
      command(room.roomId(),ControlCommandType.SET_MODE,ExecutionMode.STEP); choose(fresh,1); assertSettlement(fresh,1);
      assertFalse(lifecycle.pendingOutputs().contains(id)); assertEquals(1,runtime.invocations.get(id).get());
    } finally { faults.target=null; faults.failureEvent=null; mutable.set(DecisionExecutionIntegrationTest.MutableClock.INITIAL); }
  }
  @Test void terminalWriteRollbackRetainsPendingAndSameOutputCanRecover() throws Exception {
    Room room=fixture(); Call call=createPending(room,false); UUID id=call.input().publicRequest().decisionId();
    var pending=lifecycle.pendingOutputs(); var stored=(PendingDecisionOutputStore.Candidate)pending.acquire(id).orElseThrow();
    UUID action=stored.envelope().actionId(); pending.release(id,false);
    Room before=rooms.find(room.roomId()).orElseThrow(); faults.target="roomEvent.append";
    try { assertThrows(CompletionException.class,()->command(room.roomId(),ControlCommandType.CANCEL_DECISION,null)); }
    finally { faults.target=null; }
    assertEquals(before,rooms.find(room.roomId()).orElseThrow()); assertEquals(DecisionStatus.RUNNING,decisions.find(id).orElseThrow().status());
    assertTrue(pending.contains(id)); runtime.recoveryDone=new CountDownLatch(1);
    try {
      assertNull(cleanupCommand(room.roomId(),ControlCommandType.CONTINUE).errorCode());
      assertTrue(runtime.recoveryDone.await(5,TimeUnit.SECONDS)); assertEquals(action,records.find(id).orElseThrow().submissionResult().actionId());
      assertSettlement(call,1); assertEquals(1,runtime.invocations.get(id).get());
      assertNull(cleanupCommand(room.roomId(),ControlCommandType.PAUSE).errorCode());
      if (rooms.find(room.roomId()).orElseThrow().executionStatus()==ExecutionStatus.PAUSING) {
        Call next=runtime.next(); assertNull(cleanupCommand(room.roomId(),ControlCommandType.CANCEL_DECISION).errorCode()); choose(next,0);
      }
    } finally { runtime.recoveryDone=null; }
  }
  @Test void claimedPendingIsNotDeletedUntilOtherOwnerReleasesIt() throws Exception {
    Room room=fixture(); deadlines.scheduled.clear(); Call old=createPending(room,false); deadlines.scheduled.clear(); UUID id=old.input().publicRequest().decisionId();
    var pending=lifecycle.pendingOutputs(); assertTrue(pending.acquire(id).isPresent());
    assertNull(cleanupCommand(room.roomId(),ControlCommandType.RESET).errorCode()); assertTrue(pending.contains(id));
    assertEquals(1,deadlines.scheduled.size()); assertTrue(deadlines.scheduled.peek().delayMillis()>0);
    deadlines.firePending(); assertNull(command(room.roomId(),ControlCommandType.SET_MODE,ExecutionMode.STEP).errorCode());
    assertTrue(pending.contains(id)); assertEquals(1,deadlines.scheduled.size());
    pending.release(id,false);
    assertNull(cleanupCommand(room.roomId(),ControlCommandType.STEP).errorCode()); Call fresh=runtime.next();
    deadlines.firePending(); assertNull(cleanupCommand(room.roomId(),ControlCommandType.PAUSE).errorCode());
    assertFalse(pending.contains(id)); assertEquals(DecisionStatus.RUNNING,decisions.find(fresh.input().publicRequest().decisionId()).orElseThrow().status());
    choose(fresh,1); assertSettlement(fresh,1); assertEquals(1,runtime.invocations.get(id).get());
  }
  @Test void resetDoesNotStealPendingClaimFromRunningRecovery() throws Exception {
    Room room=fixture(); deadlines.scheduled.clear(); Call old=createPending(room,false); UUID id=old.input().publicRequest().decisionId();
    deadlines.scheduled.clear(); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
    var pool=Executors.newSingleThreadExecutor();
    var other=new DefaultAgentRunner(runtime,lifecycle,submitter,inputReader,json,UUID::randomUUID);
    try {
      var running=pool.submit(()->other.run(old.input(),()->{
        entered.countDown(); try { if (!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); } return false;
      }).toCompletableFuture().join());
      assertTrue(entered.await(5,TimeUnit.SECONDS)); assertNull(cleanupCommand(room.roomId(),ControlCommandType.RESET).errorCode());
      assertTrue(lifecycle.pendingOutputs().contains(id)); release.countDown();
      assertInstanceOf(DecisionOutcome.NotSubmitted.class,running.get(5,TimeUnit.SECONDS));
      assertFalse(lifecycle.pendingOutputs().contains(id));
      deadlines.firePending(); assertNull(command(room.roomId(),ControlCommandType.SET_MODE,ExecutionMode.STEP).errorCode());
      assertNull(cleanupCommand(room.roomId(),ControlCommandType.STEP).errorCode()); Call fresh=runtime.next(); choose(fresh,1); assertSettlement(fresh,1);
      assertEquals(1,runtime.invocations.get(id).get());
    } finally { release.countDown(); pool.shutdownNow(); }
  }
  @Test void roomEventsHaveContinuousSequenceAndMonotonicRevision() throws Exception {
    Room room=fixture(); command(room.roomId(),ControlCommandType.SET_MODE,ExecutionMode.AUTO);
    command(room.roomId(),ControlCommandType.STEP,null); Call call=runtime.next(); choose(call,0);
    command(room.roomId(),ControlCommandType.RESET,null);
    var feed=events.readAfter(room.roomId(),0,100); long sequence=0, revision=0;
    for (RoomEvent event:feed) {
      assertEquals(++sequence,event.sequence()); assertTrue(event.roomRevision()>=revision); revision=event.roomRevision();
    }
    var current=rooms.find(room.roomId()).orElseThrow(); assertEquals(sequence,current.eventSeq()); assertEquals(revision,current.revision());
  }
  @Test void repeatedTicksAndBusyStepDoNotAccumulateGrants() throws Exception {
    Room room=fixture(); command(room.roomId(),ControlCommandType.STEP,null); Call call=runtime.next();
    for (int i=0;i<20;i++) scheduler.scheduleTick(room.roomId(),room.activeMatchId(),"duplicate");
    assertEquals("CONTROL_BUSY",command(room.roomId(),ControlCommandType.STEP,null).errorCode());
    choose(call,0);
    assertEquals(ExecutionStatus.PAUSED,rooms.find(room.roomId()).orElseThrow().executionStatus());
    assertEquals(1,matches.find(room.activeMatchId()).orElseThrow().stateVersion());
    assertTrue(runtime.calls.isEmpty());
  }
  @Test void invalidRuntimeChoicePausesWithoutSettlement() throws Exception {
    Room room=fixture(); command(room.roomId(),ControlCommandType.START,null); Call call=runtime.next();
    call.future().complete(new RuntimeOutcome.SelectedAction("not-an-option")); call.finished().get(5,TimeUnit.SECONDS);
    assertEquals(DecisionStatus.FAILED,decisions.find(call.input().publicRequest().decisionId()).orElseThrow().status());
    assertEquals(0,matches.find(room.activeMatchId()).orElseThrow().stateVersion());
    assertEquals(ExecutionStatus.PAUSED,rooms.find(room.roomId()).orElseThrow().executionStatus());
  }
  @Test void tickScheduledInsideRolledBackTransactionDoesNotRun() {
    Room initial=fixture();
    new TransactionTemplate(gameTransactionManager).executeWithoutResult(tx->{
      Room room=rooms.find(initial.roomId()).orElseThrow(); Match match=matches.find(room.activeMatchId()).orElseThrow();
      assertTrue(rooms.replace(room,new Room(room.roomId(),room.revision(),room.eventSeq(),room.activeMatchId(),ExecutionMode.AUTO,ExecutionStatus.RUNNING,room.health(),room.healthError())));
      assertTrue(matches.replace(match,new Match(match.matchId(),match.roomId(),match.gameKey(),MatchStatus.ACTIVE,0,0,match.currentTurnId(),match.participants(),match.result())));
    });
    new TransactionTemplate(gameTransactionManager).executeWithoutResult(tx->{ scheduler.scheduleTick(initial.roomId(),initial.activeMatchId(),"rollback"); tx.setRollbackOnly(); });
    assertNull(command(initial.roomId(),ControlCommandType.PAUSE,null).errorCode());
    assertTrue(runtime.calls.isEmpty()); assertEquals(0,matches.find(initial.activeMatchId()).orElseThrow().stateVersion());
  }
  @Test void immediateRuntimeContinuesWithoutRecursiveExecution() throws Exception {
    Room room=fixture(); runtime.immediate.addAll(List.of(0,1,0,1,0,1,0));
    command(room.roomId(),ControlCommandType.START,null);
    for (int i=0;i<7;i++) runtime.next().finished().get(5,TimeUnit.SECONDS);
    Match match=matches.find(room.activeMatchId()).orElseThrow();
    assertEquals(MatchStatus.FINISHED,match.status()); assertEquals(7,match.stateVersion());
    assertTrue(runtime.immediate.isEmpty());
  }
  @Test void fixedSeedCommandSequencePreservesRoomAndMatchInvariants() {
    Room initial=fixture(); Random random=new Random(73219);
    for (int i=0;i<100;i++) {
      Room before=rooms.find(initial.roomId()).orElseThrow(); Match old=matches.find(before.activeMatchId()).orElseThrow();
      ControlCommandType type=switch(random.nextInt(4)) {
        case 0 -> ControlCommandType.SET_MODE; case 1 -> ControlCommandType.PAUSE;
        case 2 -> ControlCommandType.CANCEL_DECISION; default -> ControlCommandType.RESET;
      };
      var mode=type==ControlCommandType.SET_MODE?(random.nextBoolean()?ExecutionMode.AUTO:ExecutionMode.STEP):null;
      var cmd=new ControlCommand(UUID.randomUUID(),before.revision(),before.activeMatchId(),type,mode);
      CommandResult result=scheduler.handleCommand(initial.roomId(),cmd);
      assertEquals(result,scheduler.handleCommand(initial.roomId(),cmd));
      Room after=rooms.find(initial.roomId()).orElseThrow();
      assertTrue(after.revision()>=before.revision()); assertTrue(after.revision()<=before.revision()+1);
      assertTrue(after.eventSeq()>=before.eventSeq()); assertEquals(after.revision(),result.roomRevision());
      Match current=matches.find(after.activeMatchId()).orElseThrow();
      assertEquals(initial.roomId(),current.roomId()); assertEquals(0,current.stateVersion());
      assertTrue(snapshots.find(current.matchId(),0).isPresent());
      if (type==ControlCommandType.RESET) {
        assertEquals(MatchStatus.ABORTED,matches.find(old.matchId()).orElseThrow().status());
        assertEquals(MatchStatus.READY,current.status()); assertNotEquals(old.matchId(),current.matchId());
      }
    }
  }
  static Stream<Arguments> idleMatrix() {
    var cases=new ArrayList<Arguments>();
    for (MatchStatus match:MatchStatus.values()) for (ExecutionStatus status:ExecutionStatus.values())
      for (ExecutionMode mode:ExecutionMode.values()) for (ControlCommandType type:ControlCommandType.values())
        cases.add(Arguments.of(match,status,mode,type));
    return cases.stream();
  }
  static Stream<Arguments> activeMatrix() {
    var cases=new ArrayList<Arguments>();
    for (ExecutionStatus status:List.of(ExecutionStatus.RUNNING,ExecutionStatus.PAUSING))
      for (ExecutionMode mode:ExecutionMode.values()) for (ControlCommandType type:ControlCommandType.values())
        cases.add(Arguments.of(status,mode,type));
    return cases.stream();
  }
  @ParameterizedTest @MethodSource("activeMatrix")
  void activeDecisionCommandMatrix(ExecutionStatus status,ExecutionMode mode,ControlCommandType type) throws Exception {
    Room room=fixture();
    command(room.roomId(),mode==ExecutionMode.AUTO?ControlCommandType.START:ControlCommandType.STEP,null);
    Call call=runtime.next();
    if (status==ExecutionStatus.PAUSING) command(room.roomId(),ControlCommandType.PAUSE,null);
    Room before=rooms.find(room.roomId()).orElseThrow(); assertEquals(status,before.executionStatus()); assertEquals(mode,before.executionMode());
    var cmd=new ControlCommand(UUID.randomUUID(),before.revision(),before.activeMatchId(),type,type==ControlCommandType.SET_MODE?ExecutionMode.STEP:null);
    var result=scheduler.handleCommand(room.roomId(),cmd);
    String error=(type==ControlCommandType.START || type==ControlCommandType.CONTINUE || type==ControlCommandType.STEP)?"CONTROL_BUSY":null;
    assertEquals(error,result.errorCode()); assertEquals(result,scheduler.handleCommand(room.roomId(),cmd));
    if (type!=ControlCommandType.RESET) command(room.roomId(),ControlCommandType.RESET,null);
    choose(call,0); assertEquals(0,matches.find(room.activeMatchId()).orElseThrow().stateVersion());
  }
  @ParameterizedTest @MethodSource("idleMatrix")
  void idleCommandMatrix(MatchStatus state,ExecutionStatus status,ExecutionMode mode,ControlCommandType type) {
    Room initial=fixture();
    new TransactionTemplate(gameTransactionManager).executeWithoutResult(tx->{
      Room room=rooms.find(initial.roomId()).orElseThrow(); Match match=matches.find(room.activeMatchId()).orElseThrow();
      assertTrue(rooms.replace(room,new Room(room.roomId(),room.revision(),room.eventSeq(),room.activeMatchId(),mode,status,room.health(),room.healthError())));
      assertTrue(matches.replace(match,new Match(match.matchId(),match.roomId(),match.gameKey(),state,match.stateVersion(),match.gameEventSeq(),match.currentTurnId(),match.participants(),match.result())));
    });
    var cmd=new ControlCommand(UUID.randomUUID(),0,initial.activeMatchId(),type,type==ControlCommandType.SET_MODE?ExecutionMode.STEP:null);
    var execution=commands.execute(initial.roomId(),cmd); String expected=null;
    if (type!=ControlCommandType.RESET && (state==MatchStatus.FINISHED || state==MatchStatus.ABORTED)) expected="MATCH_NOT_ACTIVE";
    else if (type==ControlCommandType.START || type==ControlCommandType.CONTINUE || type==ControlCommandType.STEP) {
      if (status!=ExecutionStatus.PAUSED) expected="CONTROL_BUSY";
      else if ((type==ControlCommandType.START && state!=MatchStatus.READY) || (type==ControlCommandType.CONTINUE && state!=MatchStatus.ACTIVE)) expected="MATCH_NOT_ACTIVE";
    } else if (type==ControlCommandType.PAUSE && state!=MatchStatus.ACTIVE) expected="MATCH_NOT_ACTIVE";
    else if (type==ControlCommandType.CANCEL_DECISION) expected="NO_ACTIVE_DECISION";
    assertEquals(expected,execution.result().errorCode());
    assertEquals(execution.result(),commands.execute(initial.roomId(),cmd).result());
    assertNull(commands.execute(initial.roomId(),cmd).tickMatch());
  }
}
