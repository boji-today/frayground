package io.frayground.adapter.memory;

import io.frayground.application.port.StorageConflictException;
import io.frayground.domain.game.*;
import io.frayground.domain.match.*;
import io.frayground.domain.action.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

class InMemoryStoreSemanticsTest {
  @Test void transactionResourcesAreScopedToEachStorageInstance() {
    var a=new InMemoryStorage(); var b=new InMemoryStorage();
    var managerA=new InMemoryTransactionManager(a); var managerB=new InMemoryTransactionManager(b);
    var roomsA=new InMemoryRoomRepository(a); var roomsB=new InMemoryRoomRepository(b);
    var txA=new TransactionTemplate(managerA); var txB=new TransactionTemplate(managerB);
    UUID sharedId=UUID.randomUUID(), bId=UUID.randomUUID(), aSecondId=UUID.randomUUID();
    Room fromA=new Room(sharedId,0,0,null,ExecutionMode.STEP,ExecutionStatus.PAUSED,RoomHealth.OK,null);
    Room fromB=new Room(bId,0,0,null,ExecutionMode.STEP,ExecutionStatus.PAUSED,RoomHealth.OK,null);
    txA.executeWithoutResult(status->{
      roomsA.insert(fromA);
      assertTrue(roomsA.find(sharedId).isPresent());
      assertTrue(roomsB.find(sharedId).isEmpty());
      assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->roomsB.insert(fromB));
      txB.executeWithoutResult(nested->{roomsB.insert(fromB);assertTrue(roomsB.find(bId).isPresent());});
      assertTrue(roomsA.find(bId).isEmpty());
      roomsA.insert(new Room(aSecondId,0,0,null,ExecutionMode.STEP,ExecutionStatus.PAUSED,RoomHealth.OK,null));
    });
    assertTrue(roomsA.find(sharedId).isPresent());
    assertTrue(roomsA.find(aSecondId).isPresent());
    assertTrue(roomsB.find(bId).isPresent());
    assertTrue(roomsB.find(sharedId).isEmpty());
  }
  @Test void externalReadersSeeCommittedImageUntilCommit() throws Exception {
    InMemoryStorage storage=new InMemoryStorage();
    var manager=new InMemoryTransactionManager(storage); var rooms=new InMemoryRoomRepository(storage);
    var tx=new TransactionTemplate(manager); var room=new Room(UUID.randomUUID(),0,0,null,ExecutionMode.STEP,ExecutionStatus.PAUSED,RoomHealth.OK,null);
    CountDownLatch written=new CountDownLatch(1), release=new CountDownLatch(1);
    ExecutorService pool=Executors.newSingleThreadExecutor();
    try {
      Future<?> future=pool.submit(()->tx.executeWithoutResult(status->{ rooms.insert(room); assertTrue(rooms.find(room.roomId()).isPresent()); written.countDown(); try { assertTrue(release.await(5,TimeUnit.SECONDS)); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); } }));
      assertTrue(written.await(5,TimeUnit.SECONDS));
      assertTrue(rooms.find(room.roomId()).isEmpty());
      release.countDown(); future.get(5,TimeUnit.SECONDS);
      assertTrue(rooms.find(room.roomId()).isPresent());
    } finally { release.countDown(); pool.shutdownNow(); }
  }
  @Test void concurrentMatchVersionCasUpdatesExactlyOnce() throws Exception {
    InMemoryStorage storage=new InMemoryStorage();
    InMemoryTransactionManager manager=new InMemoryTransactionManager(storage);
    var rooms=new InMemoryRoomRepository(storage); var matches=new InMemoryMatchRepository(storage);
    var tx=new TransactionTemplate(manager);
    UUID roomId=UUID.randomUUID(), matchId=UUID.randomUUID();
    tx.executeWithoutResult(s->{rooms.insert(new Room(roomId,0,0,null,ExecutionMode.STEP,ExecutionStatus.PAUSED,RoomHealth.OK,null)); matches.insert(new Match(matchId,roomId,new GameKey("cas","1"),MatchStatus.READY,0,0,null,List.of(),null));});
    CyclicBarrier start=new CyclicBarrier(2);
    ExecutorService pool=Executors.newFixedThreadPool(2);
    try {
      Callable<Boolean> attempt=()->{start.await(); final boolean[] changed={false}; tx.executeWithoutResult(s->changed[0]=matches.compareAndSetVersion(matchId,0,1)); return changed[0];};
      Future<Boolean> a=pool.submit(attempt), b=pool.submit(attempt);
      assertNotEquals(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS));
      assertEquals(1,matches.find(matchId).orElseThrow().stateVersion());
    } finally { pool.shutdownNow(); }
  }
  @Test void eventsAreAppendOnlyIsolatedAndDeepCopied() {
    InMemoryStorage storage=new InMemoryStorage();
    InMemoryTransactionManager manager=new InMemoryTransactionManager(storage);
    var rooms=new InMemoryRoomRepository(storage); var matches=new InMemoryMatchRepository(storage);
    var events=new InMemoryGameEventRepository(storage);
    UUID roomId=UUID.randomUUID(), matchId=UUID.randomUUID();
    Room room=new Room(roomId,0,0,null,ExecutionMode.STEP,ExecutionStatus.PAUSED,RoomHealth.OK,null);
    Match match=new Match(matchId,roomId,new GameKey("test","1"),MatchStatus.READY,0,0,null,List.of(),null);
    TransactionTemplate tx=new TransactionTemplate(manager);
    tx.executeWithoutResult(s->{rooms.insert(room);matches.insert(match);});
    byte[] bytes={1,2}; List<Object> list=new ArrayList<>(List.of("x",bytes));
    GameEvent event=new GameEvent(matchId,1,0,null,"test",1,Map.of("items",list),Instant.EPOCH);
    tx.executeWithoutResult(s->events.append(event));
    bytes[0]=9; list.add("later");
    GameEvent read=events.readAfter(matchId,0,2).getFirst();
    assertEquals(0,events.readAfter(matchId,1,10).size());
    assertEquals(2,((List<?>)read.payload().get("items")).size());
    byte[] returned=(byte[])((List<?>)read.payload().get("items")).get(1);
    returned[0]=7;
    assertEquals(1,((byte[])((List<?>)events.readAfter(matchId,0,1).getFirst().payload().get("items")).get(1))[0]);
    GameEvent conflict=new GameEvent(matchId,1,0,null,"different",1,Map.of(),Instant.EPOCH);
    assertThrows(StorageConflictException.class,()->tx.executeWithoutResult(s->events.append(conflict)));
    assertEquals(1,events.readAfter(matchId,0,10).size());
    assertTrue(events.readAfter(UUID.randomUUID(),0,10).isEmpty());
  }

  @Test void snapshotSettlementAndReceiptsAreIdempotentAndDefensivelyCopied() {
    InMemoryStorage storage=new InMemoryStorage(); var manager=new InMemoryTransactionManager(storage); var tx=new TransactionTemplate(manager);
    var rooms=new InMemoryRoomRepository(storage); var matches=new InMemoryMatchRepository(storage);
    var turns=new InMemoryTurnRepository(storage); var decisions=new InMemoryDecisionRepository(storage);
    var snapshots=new InMemorySnapshotStore(storage); var settlements=new InMemorySettlementRepository(storage);
    var commands=new InMemoryCommandReceiptRepository(storage); var actions=new InMemoryActionReceiptRepository(storage);
    UUID roomId=UUID.randomUUID(),matchId=UUID.randomUUID(),turnId=UUID.randomUUID(),participantId=UUID.randomUUID(),decisionId=UUID.randomUUID();
    Room room=new Room(roomId,0,0,null,ExecutionMode.STEP,ExecutionStatus.PAUSED,RoomHealth.OK,null);
    Match match=new Match(matchId,roomId,new GameKey("audit","1"),MatchStatus.READY,0,0,turnId,List.of(new Participant(participantId,UUID.randomUUID(),"seat")),null);
    TurnContext turn=new TurnContext(turnId,1,1,0,SchedulingMode.TURN_BASED_SEQUENTIAL,List.of(participantId),TurnStatus.OPEN);
    Decision decision=new Decision(decisionId,matchId,turnId,participantId,DecisionStatus.RUNNING,0,0,null,null,"{}",Instant.EPOCH,Instant.EPOCH,null,null);
    tx.executeWithoutResult(s->{rooms.insert(room);matches.insert(match);turns.insert(matchId,turn);decisions.insert(decision);});
    byte[] stateBytes={4}; var state=Map.<String,Object>of("bytes",stateBytes); StateSnapshot snapshot=new StateSnapshot(matchId,0,state,Instant.EPOCH);
    tx.executeWithoutResult(s->{snapshots.append(snapshot);snapshots.append(snapshot);}); stateBytes[0]=8;
    assertEquals(4,((byte[])snapshots.find(matchId,0).orElseThrow().state().get("bytes"))[0]);
    var nestedAction=new ArrayList<Object>(List.of("a"));
    var settlement=new Settlement(UUID.randomUUID(),matchId,turnId,0,1,List.of(new SettledAction(participantId,decisionId,UUID.randomUUID(),Map.of("nested",nestedAction))));
    var expectedSettlement=new Settlement(settlement.settlementId(),matchId,turnId,0,1,List.of(new SettledAction(participantId,decisionId,settlement.actions().getFirst().actionId(),Map.of("nested",List.of("a")))));
    tx.executeWithoutResult(s->{settlements.save(settlement);settlements.save(settlement);});
    nestedAction.add("outside mutation");
    assertEquals(expectedSettlement,settlements.findBySettlementId(settlement.settlementId()).orElseThrow());
    var readSettlement=settlements.findByTurnId(turnId).orElseThrow();
    assertEquals(1,((List<?>)readSettlement.actions().getFirst().action().get("nested")).size());
    assertThrows(UnsupportedOperationException.class,()->((List<Object>)readSettlement.actions().getFirst().action().get("nested")).add("query mutation"));
    UUID rollbackTurnId=UUID.randomUUID(), rollbackDecisionId=UUID.randomUUID();
    Settlement rollbackSettlement=new Settlement(UUID.randomUUID(),matchId,rollbackTurnId,0,1,List.of(new SettledAction(participantId,rollbackDecisionId,UUID.randomUUID(),Map.of("n",List.of(1)))));
    assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(s->{turns.insert(matchId,new TurnContext(rollbackTurnId,2,2,0,SchedulingMode.TURN_BASED_SEQUENTIAL,List.of(participantId),TurnStatus.OPEN)); decisions.insert(new Decision(rollbackDecisionId,matchId,rollbackTurnId,participantId,DecisionStatus.RUNNING,0,0,null,null,"{}",Instant.EPOCH,Instant.EPOCH,null,null)); settlements.save(rollbackSettlement); throw new IllegalStateException("rollback");}));
    assertTrue(settlements.findBySettlementId(rollbackSettlement.settlementId()).isEmpty());
    assertThrows(StorageConflictException.class,()->tx.executeWithoutResult(s->settlements.save(new Settlement(UUID.randomUUID(),matchId,turnId,0,1,List.of(new SettledAction(participantId,decisionId,UUID.randomUUID(),Map.of("move",2)))))));
    byte[] hash={3,4}; var commandResult=new CommandResult(UUID.randomUUID(),null,0,matchId,ExecutionMode.STEP,ExecutionStatus.PAUSED); UUID commandId=commandResult.commandId();
    tx.executeWithoutResult(s->{commands.save(roomId,commandId,hash,commandResult);commands.save(roomId,commandId,hash,commandResult);}); hash[0]=9;
    byte[] hashRead=commands.findRequestHash(roomId,commandId).orElseThrow(); assertEquals(3,hashRead[0]); hashRead[0]=1; assertEquals(3,commands.findRequestHash(roomId,commandId).orElseThrow()[0]);
    UUID actionId=UUID.randomUUID(); ActionEnvelope envelope=new ActionEnvelope("1.0",matchId,participantId,turnId,decisionId,actionId,0,Map.of("bytes",new byte[]{5}));
    ActionSubmissionResult result=new ActionSubmissionResult(SubmissionStatus.REJECTED,actionId,decisionId,matchId,0,null,"INVALID");
    tx.executeWithoutResult(s->{actions.saveFinal(envelope,result);actions.saveFinal(envelope,result);});
    ((byte[])envelope.action().get("bytes"))[0]=0;
    assertEquals(5,((byte[])actions.findEnvelope(actionId).orElseThrow().action().get("bytes"))[0]);
  }
  @Test void decisionParticipantMustBelongToMatchAndCannotBeReplaced() {
    var storage=new InMemoryStorage(); var tx=new TransactionTemplate(new InMemoryTransactionManager(storage));
    var rooms=new InMemoryRoomRepository(storage); var matches=new InMemoryMatchRepository(storage); var turns=new InMemoryTurnRepository(storage); var decisions=new InMemoryDecisionRepository(storage);
    UUID roomId=UUID.randomUUID(),matchId=UUID.randomUUID(),turnId=UUID.randomUUID(),participantId=UUID.randomUUID();
    var room=new Room(roomId,0,0,null,ExecutionMode.STEP,ExecutionStatus.PAUSED,RoomHealth.OK,null);
    var match=new Match(matchId,roomId,new GameKey("participant-check","1"),MatchStatus.READY,0,0,turnId,List.of(new Participant(participantId,UUID.randomUUID(),"p")),null);
    var turn=new TurnContext(turnId,1,1,0,SchedulingMode.TURN_BASED_SEQUENTIAL,List.of(participantId),TurnStatus.OPEN);
    tx.executeWithoutResult(s->{rooms.insert(room);matches.insert(match);turns.insert(matchId,turn);});
    UUID unknown=UUID.randomUUID(), decisionId=UUID.randomUUID();
    var invalid=new Decision(UUID.randomUUID(),matchId,turnId,unknown,DecisionStatus.PENDING,0,0,null,null,null,null,null,null,null);
    assertThrows(StorageConflictException.class,()->tx.executeWithoutResult(s->decisions.insert(invalid)));
    var valid=new Decision(decisionId,matchId,turnId,participantId,DecisionStatus.PENDING,0,0,null,null,null,null,null,null,null);
    tx.executeWithoutResult(s->decisions.insert(valid));
    var changedParticipant=new Decision(decisionId,matchId,turnId,unknown,DecisionStatus.RUNNING,0,0,null,null,null,null,null,null,null);
    assertThrows(IllegalArgumentException.class,()->tx.executeWithoutResult(s->decisions.replace(valid,changedParticipant)));
    assertEquals(valid,decisions.find(decisionId).orElseThrow());
  }
}
