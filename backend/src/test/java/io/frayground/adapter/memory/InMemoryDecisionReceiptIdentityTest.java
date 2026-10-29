package io.frayground.adapter.memory;

import io.frayground.application.port.StorageConflictException;
import io.frayground.domain.action.ActionEnvelope;
import io.frayground.domain.action.ActionSubmissionResult;
import io.frayground.domain.action.Decision;
import io.frayground.domain.action.DecisionRecord;
import io.frayground.domain.action.DecisionStatus;
import io.frayground.domain.action.SettledAction;
import io.frayground.domain.action.Settlement;
import io.frayground.domain.action.SubmissionStatus;
import io.frayground.domain.game.GameKey;
import io.frayground.domain.game.Participant;
import io.frayground.domain.game.SchedulingMode;
import io.frayground.domain.match.CommandResult;
import io.frayground.domain.match.ExecutionMode;
import io.frayground.domain.match.ExecutionStatus;
import io.frayground.domain.match.Match;
import io.frayground.domain.match.MatchStatus;
import io.frayground.domain.match.Room;
import io.frayground.domain.match.RoomHealth;
import io.frayground.domain.match.TurnContext;
import io.frayground.domain.match.TurnStatus;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryDecisionReceiptIdentityTest {
  @Test void decisionInsertRejectsUnrequiredParticipantAndVersion() {
    World world=world();
    UUID unrequiredId=UUID.randomUUID();
    Decision unrequired=decision(unrequiredId,world.matchA,world.turnA,world.participantSpare,0);
    assertThrows(StorageConflictException.class,()->world.tx.executeWithoutResult(s->world.decisions.insert(unrequired)));
    assertTrue(world.decisions.find(unrequiredId).isEmpty());
    UUID staleId=UUID.randomUUID();
    Decision stale=decision(staleId,world.matchA,world.turnA,world.participantA,1);
    assertThrows(StorageConflictException.class,()->world.tx.executeWithoutResult(s->world.decisions.insert(stale)));
    assertTrue(world.decisions.find(staleId).isEmpty());
    assertEquals(world.decisionAStored,world.decisions.find(world.decisionA).orElseThrow());
    UUID retryId=UUID.randomUUID();
    Decision retry=decision(retryId,world.matchA,world.turnA,world.participantA,0);
    world.tx.executeWithoutResult(s->world.decisions.insert(retry));
    assertEquals(retry,world.decisions.find(retryId).orElseThrow());
    assertEquals(world.decisionBStored,world.decisions.find(world.decisionB).orElseThrow());
  }

  @Test void actionReceiptRejectsCrossedEnvelope() {
    World world=world();
    for (SubmissionStatus status : List.of(SubmissionStatus.EXECUTED,SubmissionStatus.ACCEPTED)) {
      rejectEnvelope(world,envelope(world.matchB,world.participantB,world.turnB,world.decisionA,UUID.randomUUID(),0),status);
      rejectEnvelope(world,envelope(world.matchA,world.participantA,world.turnB,world.decisionA,UUID.randomUUID(),0),status);
      rejectEnvelope(world,envelope(world.matchA,world.participantSpare,world.turnA,world.decisionA,UUID.randomUUID(),0),status);
      rejectEnvelope(world,envelope(world.matchA,world.participantA,world.turnA,world.decisionA,UUID.randomUUID(),1),status);
    }
    UUID actionId=UUID.randomUUID();
    ActionEnvelope owned=envelope(world.matchA,world.participantA,world.turnA,world.decisionA,actionId,0);
    ActionSubmissionResult executed=new ActionSubmissionResult(SubmissionStatus.EXECUTED,actionId,world.decisionA,world.matchA,1,Map.of("column",1),null);
    world.tx.executeWithoutResult(s->world.actions.saveFinal(owned,executed));
    assertEquals(owned,world.actions.findEnvelope(actionId).orElseThrow());
    assertEquals(executed,world.actions.findByActionId(actionId).orElseThrow());
    UUID acceptedId=UUID.randomUUID();
    ActionEnvelope acceptedEnvelope=envelope(world.matchA,world.participantA,world.turnA,world.decisionA,acceptedId,0);
    ActionSubmissionResult accepted=new ActionSubmissionResult(SubmissionStatus.ACCEPTED,acceptedId,world.decisionA,world.matchA,0,null,null);
    world.tx.executeWithoutResult(s->world.actions.saveFinal(acceptedEnvelope,accepted));
    assertEquals(acceptedEnvelope,world.actions.findEnvelope(acceptedId).orElseThrow());
    assertEquals(accepted,world.actions.findByActionId(acceptedId).orElseThrow());
  }

  @Test void rejectedMismatchedRequestsPreserveBothAggregates() {
    World world=world();
    for (ActionEnvelope request : List.of(
        envelope(world.matchA,world.participantA,world.turnB,world.decisionA,UUID.randomUUID(),0),
        envelope(world.matchA,world.participantSpare,world.turnA,world.decisionA,UUID.randomUUID(),0),
        envelope(world.matchB,world.participantB,world.turnB,world.decisionA,UUID.randomUUID(),0))) {
      ActionSubmissionResult rejected=new ActionSubmissionResult(SubmissionStatus.REJECTED,request.actionId(),request.decisionId(),request.matchId(),0,null,"INVALID");
      world.tx.executeWithoutResult(s->world.actions.saveFinal(request,rejected));
      assertEquals(request,world.actions.findEnvelope(request.actionId()).orElseThrow());
      assertEquals(rejected,world.actions.findByActionId(request.actionId()).orElseThrow());
    }
    assertEquals(world.decisionAStored,world.decisions.find(world.decisionA).orElseThrow());
    assertEquals(world.decisionBStored,world.decisions.find(world.decisionB).orElseThrow());
    assertEquals(world.matchAStored,world.matches.find(world.matchA).orElseThrow());
    assertEquals(world.matchBStored,world.matches.find(world.matchB).orElseThrow());
    assertEquals(world.turnAStored,world.turns.find(world.turnA).orElseThrow());
    assertEquals(world.turnBStored,world.turns.find(world.turnB).orElseThrow());
  }

  private static void rejectEnvelope(World world,ActionEnvelope envelope,SubmissionStatus status) {
    ActionSubmissionResult result=new ActionSubmissionResult(status,envelope.actionId(),envelope.decisionId(),envelope.matchId(),0,status==SubmissionStatus.EXECUTED?Map.of("column",1):null,null);
    assertThrows(StorageConflictException.class,()->world.tx.executeWithoutResult(s->world.actions.saveFinal(envelope,result)));
    assertTrue(world.actions.findByActionId(envelope.actionId()).isEmpty());
    assertTrue(world.actions.findEnvelope(envelope.actionId()).isEmpty());
  }

  @Test void staleRejectionIsStoredIdempotentlyWithoutChangingState() {
    World world=world();
    UUID actionId=UUID.randomUUID();
    ActionEnvelope stale=envelope(world.matchA,world.participantA,world.turnA,world.decisionA,actionId,1);
    ActionSubmissionResult rejected=new ActionSubmissionResult(SubmissionStatus.REJECTED,actionId,world.decisionA,world.matchA,0,null,"STALE_STATE");
    world.tx.executeWithoutResult(s->{world.actions.saveFinal(stale,rejected);world.actions.saveFinal(stale,rejected);});
    assertEquals(stale,world.actions.findEnvelope(actionId).orElseThrow());
    assertEquals(rejected,world.actions.findByActionId(actionId).orElseThrow());
    assertEquals(world.decisionAStored,world.decisions.find(world.decisionA).orElseThrow());
    assertEquals(world.matchAStored,world.matches.find(world.matchA).orElseThrow());
    assertEquals(world.turnAStored,world.turns.find(world.turnA).orElseThrow());
    ActionEnvelope conflicting=envelope(world.matchA,world.participantA,world.turnA,world.decisionA,actionId,2);
    assertThrows(StorageConflictException.class,()->world.tx.executeWithoutResult(s->world.actions.saveFinal(conflicting,rejected)));
    ActionSubmissionResult changed=new ActionSubmissionResult(SubmissionStatus.REJECTED,actionId,world.decisionA,world.matchA,0,null,"TURN_MISMATCH");
    assertThrows(StorageConflictException.class,()->world.tx.executeWithoutResult(s->world.actions.saveFinal(stale,changed)));
    assertEquals(stale,world.actions.findEnvelope(actionId).orElseThrow());
    assertEquals(rejected,world.actions.findByActionId(actionId).orElseThrow());
  }

  @Test void rejectionStillRequiresMatchingReceiptIdentityAndExistingOwners() {
    World world=world();
    ActionEnvelope envelope=envelope(world.matchA,world.participantA,world.turnA,world.decisionA,UUID.randomUUID(),1);
    for (ActionSubmissionResult result : List.of(
        new ActionSubmissionResult(SubmissionStatus.REJECTED,UUID.randomUUID(),world.decisionA,world.matchA,0,null,"STALE_STATE"),
        new ActionSubmissionResult(SubmissionStatus.REJECTED,envelope.actionId(),world.decisionB,world.matchA,0,null,"STALE_STATE"),
        new ActionSubmissionResult(SubmissionStatus.REJECTED,envelope.actionId(),world.decisionA,world.matchB,0,null,"STALE_STATE"))) {
      assertThrows(StorageConflictException.class,()->world.tx.executeWithoutResult(s->world.actions.saveFinal(envelope,result)));
    }
    ActionEnvelope unknown=envelope(world.matchA,world.participantA,world.turnA,UUID.randomUUID(),UUID.randomUUID(),0);
    ActionSubmissionResult rejected=new ActionSubmissionResult(SubmissionStatus.REJECTED,unknown.actionId(),unknown.decisionId(),unknown.matchId(),0,null,"INVALID");
    assertThrows(StorageConflictException.class,()->world.tx.executeWithoutResult(s->world.actions.saveFinal(unknown,rejected)));
    assertTrue(world.actions.findByActionId(envelope.actionId()).isEmpty());
  }

  @Test void settlementRejectsCrossedParticipant() {
    World world=world();
    UUID crossedId=UUID.randomUUID();
    Settlement crossed=settlement(crossedId,world.matchA,world.turnA,world.participantB,world.decisionA);
    assertThrows(StorageConflictException.class,()->world.tx.executeWithoutResult(s->world.settlements.save(crossed)));
    assertTrue(world.settlements.findBySettlementId(crossedId).isEmpty());
    assertTrue(world.settlements.findByTurnId(world.turnA).isEmpty());
    UUID settlementId=UUID.randomUUID();
    Settlement owned=settlement(settlementId,world.matchA,world.turnA,world.participantA,world.decisionA);
    world.tx.executeWithoutResult(s->world.settlements.save(owned));
    assertEquals(owned,world.settlements.findBySettlementId(settlementId).orElseThrow());
    assertEquals(owned,world.settlements.findByTurnId(world.turnA).orElseThrow());
  }

  @Test void decisionRecordRejectsMismatchedReceipt() {
    World world=world();
    ActionSubmissionResult wrongDecision=receipt(SubmissionStatus.REJECTED,world.decisionB,world.matchA,0,null,"INVALID");
    DecisionRecord crossed=record(world.decisionA,wrongDecision);
    assertThrows(StorageConflictException.class,()->world.tx.executeWithoutResult(s->world.records.insert(crossed)));
    assertTrue(world.records.find(world.decisionA).isEmpty());
    DecisionRecord stored=record(world.decisionA,null);
    world.tx.executeWithoutResult(s->world.records.insert(stored));
    ActionSubmissionResult wrongMatch=receipt(SubmissionStatus.REJECTED,world.decisionA,world.matchB,0,null,"INVALID");
    assertThrows(StorageConflictException.class,()->world.tx.executeWithoutResult(s->world.records.replace(stored,record(world.decisionA,wrongMatch))));
    assertEquals(stored,world.records.find(world.decisionA).orElseThrow());
    ActionSubmissionResult executed=receipt(SubmissionStatus.EXECUTED,world.decisionA,world.matchA,1,Map.of("column",1),null);
    DecisionRecord updated=record(world.decisionA,executed);
    boolean replaced=world.tx.execute(s->world.records.replace(stored,updated));
    assertTrue(replaced);
    assertEquals(updated,world.records.find(world.decisionA).orElseThrow());
  }

  @Test void commandReceiptRejectsMismatchedIdAndForeignMatch() {
    World world=world();
    UUID commandId=UUID.randomUUID();
    byte[] hash={1,2};
    CommandResult owned=command(commandId,world.matchA);
    assertThrows(StorageConflictException.class,()->world.tx.executeWithoutResult(s->world.commands.save(world.roomA,UUID.randomUUID(),hash,owned)));
    assertTrue(world.commands.find(world.roomA,commandId).isEmpty());
    CommandResult foreign=command(commandId,world.matchB);
    assertThrows(StorageConflictException.class,()->world.tx.executeWithoutResult(s->world.commands.save(world.roomA,commandId,hash,foreign)));
    assertTrue(world.commands.find(world.roomA,commandId).isEmpty());
    world.tx.executeWithoutResult(s->{world.commands.save(world.roomA,commandId,hash,owned);world.commands.save(world.roomA,commandId,hash,owned);});
    assertEquals(owned,world.commands.find(world.roomA,commandId).orElseThrow());
    assertTrue(world.commands.find(world.roomB,commandId).isEmpty());
  }

  private static World world() {
    World world=new World();
    world.tx.executeWithoutResult(s->{
      world.rooms.insert(world.roomAStored); world.rooms.insert(world.roomBStored);
      world.matches.insert(world.matchAStored); world.matches.insert(world.matchBStored);
      world.turns.insert(world.matchA,world.turnAStored); world.turns.insert(world.matchB,world.turnBStored);
      world.decisions.insert(world.decisionAStored); world.decisions.insert(world.decisionBStored);
    });
    return world;
  }

  private static Room room(UUID roomId) {
    return new Room(roomId,0,0,null,ExecutionMode.STEP,ExecutionStatus.PAUSED,RoomHealth.OK,null);
  }

  private static Match match(UUID matchId,UUID roomId,List<Participant> participants) {
    return new Match(matchId,roomId,new GameKey("identity","1"),MatchStatus.READY,0,0,null,participants,null);
  }

  private static TurnContext turn(UUID turnId,UUID participantId) {
    return new TurnContext(turnId,1,1,0,SchedulingMode.TURN_BASED_SEQUENTIAL,List.of(participantId),TurnStatus.OPEN);
  }

  private static Decision decision(UUID decisionId,UUID matchId,UUID turnId,UUID participantId,long stateVersion) {
    return new Decision(decisionId,matchId,turnId,participantId,DecisionStatus.PENDING,stateVersion,0,null,null,null,null,null,null,null);
  }

  private static ActionEnvelope envelope(UUID matchId,UUID participantId,UUID turnId,UUID decisionId,UUID actionId,long stateVersion) {
    return new ActionEnvelope("1.0",matchId,participantId,turnId,decisionId,actionId,stateVersion,Map.of("column",1));
  }

  private static Settlement settlement(UUID settlementId,UUID matchId,UUID turnId,UUID participantId,UUID decisionId) {
    return new Settlement(settlementId,matchId,turnId,0,1,List.of(new SettledAction(participantId,decisionId,UUID.randomUUID(),Map.of("column",1))));
  }

  private static ActionSubmissionResult receipt(SubmissionStatus status,UUID decisionId,UUID matchId,long stateVersion,Map<String,Object> executed,String errorCode) {
    return new ActionSubmissionResult(status,UUID.randomUUID(),decisionId,matchId,stateVersion,executed,errorCode);
  }

  private static DecisionRecord record(UUID decisionId,ActionSubmissionResult result) {
    return new DecisionRecord(decisionId,null,null,null,null,null,result,null,null,null,null);
  }

  private static CommandResult command(UUID commandId,UUID matchId) {
    return new CommandResult(commandId,null,0,matchId,ExecutionMode.STEP,ExecutionStatus.PAUSED);
  }

  private static final class World {
    final InMemoryStorage storage=new InMemoryStorage();
    final TransactionTemplate tx=new TransactionTemplate(new InMemoryTransactionManager(storage));
    final InMemoryRoomRepository rooms=new InMemoryRoomRepository(storage);
    final InMemoryMatchRepository matches=new InMemoryMatchRepository(storage);
    final InMemoryTurnRepository turns=new InMemoryTurnRepository(storage);
    final InMemoryDecisionRepository decisions=new InMemoryDecisionRepository(storage);
    final InMemoryActionReceiptRepository actions=new InMemoryActionReceiptRepository(storage);
    final InMemorySettlementRepository settlements=new InMemorySettlementRepository(storage);
    final InMemoryDecisionRecordRepository records=new InMemoryDecisionRecordRepository(storage);
    final InMemoryCommandReceiptRepository commands=new InMemoryCommandReceiptRepository(storage);
    final UUID roomA=UUID.randomUUID(), roomB=UUID.randomUUID();
    final UUID matchA=UUID.randomUUID(), matchB=UUID.randomUUID();
    final UUID turnA=UUID.randomUUID(), turnB=UUID.randomUUID();
    final UUID participantA=UUID.randomUUID(), participantSpare=UUID.randomUUID(), participantB=UUID.randomUUID();
    final UUID decisionA=UUID.randomUUID(), decisionB=UUID.randomUUID();
    final Room roomAStored=room(roomA), roomBStored=room(roomB);
    final Match matchAStored=match(matchA,roomA,List.of(new Participant(participantA,UUID.randomUUID(),"A"),new Participant(participantSpare,UUID.randomUUID(),"spare")));
    final Match matchBStored=match(matchB,roomB,List.of(new Participant(participantB,UUID.randomUUID(),"B")));
    final TurnContext turnAStored=turn(turnA,participantA), turnBStored=turn(turnB,participantB);
    final Decision decisionAStored=decision(decisionA,matchA,turnA,participantA,0), decisionBStored=decision(decisionB,matchB,turnB,participantB,0);
  }
}
