package io.frayground.application;

import io.frayground.application.port.*;
import io.frayground.domain.action.*;
import io.frayground.domain.game.*;
import io.frayground.domain.match.*;
import io.frayground.engine.GameActionRejectedException;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.type.TypeReference;

/** Serializes submission under the owning room lock and settles in one transaction. */
public class TransactionalActionSubmitter implements ActionSubmitter {
  private final RoomStore rooms; private final MatchStore matches; private final TurnStore turns;
  private final DecisionStore decisions; private final DecisionRecordStore records; private final SnapshotStore snapshots;
  private final GameEventStore gameEvents; private final SettlementStore settlements; private final ActionReceiptStore receipts;
  private final DecisionGameAccess games; private final DecisionExecutionService lifecycle; private final Clock clock;
  private final Supplier<UUID> ids; private final ActionEnvelopeCanonicalizer canonicalizer; private final JsonMapper json;
  public TransactionalActionSubmitter(RoomStore rooms, MatchStore matches, TurnStore turns, DecisionStore decisions,
      DecisionRecordStore records, SnapshotStore snapshots, GameEventStore gameEvents, SettlementStore settlements,
      ActionReceiptStore receipts, DecisionGameAccess games, DecisionExecutionService lifecycle, Clock clock,
      Supplier<UUID> ids, ActionEnvelopeCanonicalizer canonicalizer, JsonMapper json) {
    this.rooms=rooms; this.matches=matches; this.turns=turns; this.decisions=decisions; this.records=records;
    this.snapshots=snapshots; this.gameEvents=gameEvents; this.settlements=settlements; this.receipts=receipts;
    this.games=games; this.lifecycle=lifecycle; this.clock=clock; this.ids=ids; this.canonicalizer=canonicalizer; this.json=json;
  }
  @Override @Transactional(transactionManager="gameTransactionManager")
  public CompletionStage<ActionSubmissionResult> submit(ActionEnvelope e) {
    Objects.requireNonNull(e);
    var priorEnvelope=receipts.findEnvelope(e.actionId());
    var known=matches.find(priorEnvelope.map(ActionEnvelope::matchId).orElse(e.matchId()));
    if (known.isEmpty()) return CompletableFuture.completedFuture(rejection(e,"MATCH_NOT_FOUND",e.stateVersion()));
    rooms.lock(known.get().roomId());
    var existing=receipts.findByActionId(e.actionId());
    if (existing.isPresent()) {
      var original=DecisionExecutionService.present(receipts.findEnvelope(e.actionId()),"RECEIPT_ENVELOPE_MISSING");
      return CompletableFuture.completedFuture(canonicalizer.equivalent(original,e) ? existing.get() : rejection(e,"ACTION_ID_CONFLICT",e.stateVersion()));
    }
    Match match=DecisionExecutionService.present(matches.find(e.matchId()),"MATCH_NOT_FOUND");
    Room room=DecisionExecutionService.present(rooms.find(match.roomId()),"ROOM_NOT_FOUND");
    Decision decision=decisions.find(e.decisionId()).orElse(null);
    TurnContext turn=turns.find(e.turnId()).orElse(null);
    String error=null;
    if (!Objects.equals(room.activeMatchId(),match.matchId()) || match.status()!=MatchStatus.ACTIVE) error="MATCH_NOT_ACTIVE";
    else if (room.health()!=RoomHealth.OK || (room.executionStatus()!=ExecutionStatus.RUNNING && room.executionStatus()!=ExecutionStatus.PAUSING)) error="ROOM_NOT_RUNNING";
    else if (turn==null || !Objects.equals(match.currentTurnId(),e.turnId()) || turn.status()!=TurnStatus.OPEN) error="TURN_NOT_CURRENT";
    else if (turn.schedulingMode()!=SchedulingMode.TURN_BASED_SEQUENTIAL) error="SCHEDULING_MODE_NOT_SUPPORTED";
    else if (!turn.requiredParticipants().contains(e.participantId()) || match.participants().stream().noneMatch(p->p.participantId().equals(e.participantId()))) error="PARTICIPANT_MISMATCH";
    else if (decision==null || !decision.matchId().equals(e.matchId()) || !decision.turnId().equals(e.turnId()) || !decision.participantId().equals(e.participantId())) error="DECISION_MISMATCH";
    else if (decision.status()!=DecisionStatus.RUNNING) error="DECISION_NOT_RUNNING";
    else if (e.stateVersion()!=match.stateVersion() || decision.stateVersion()!=match.stateVersion() || turn.baseStateVersion()!=match.stateVersion()) error="STATE_VERSION_MISMATCH";
    if (error!=null) return doneReject(e,error,match.stateVersion());
    if (!clock.instant().isBefore(decision.deadlineAt())) {
      lifecycle.expire(decision.decisionId());
      return doneReject(e,"DECISION_EXPIRED",match.stateVersion());
    }
    var snapshot=DecisionExecutionService.present(snapshots.find(match.matchId(),match.stateVersion()),"SNAPSHOT_NOT_FOUND");
    DecisionGameAccess.Applied applied;
    try { applied=games.apply(match.gameKey(),match.stateVersion(),snapshot.state(),match.participants(),e.participantId(),e.action()); }
    catch (GameActionRejectedException rejected) { return invalid(e,match,decision,rejected.code()); }
    long version=match.stateVersion()+1;
    boolean finished=applied.result().status()!=GameOutcome.ONGOING;
    TurnContext next=null;
    if (!finished) {
      var plan=Objects.requireNonNull(applied.nextPlan());
      DecisionExecutionService.require(plan.mode()==SchedulingMode.TURN_BASED_SEQUENTIAL,"SCHEDULING_MODE_NOT_SUPPORTED");
      DecisionExecutionService.require(plan.requiredParticipants().size()==1 && match.participants().stream().anyMatch(p->p.participantId().equals(plan.requiredParticipants().get(0))),"INVALID_TURN_PLAN");
      long turnNo=turn.turnNo()+1;
      next=new TurnContext(ids.get(),turnNo,games.roundNo(match.gameKey(),turnNo),version,plan.mode(),plan.requiredParticipants(),TurnStatus.OPEN);
    }
    ActionSubmissionResult result=new ActionSubmissionResult(SubmissionStatus.EXECUTED,e.actionId(),e.decisionId(),e.matchId(),version,applied.action(),null);
    receipts.saveFinal(e,result);
    settlements.save(new Settlement(ids.get(),match.matchId(),turn.turnId(),match.stateVersion(),version,
        List.of(new SettledAction(e.participantId(),e.decisionId(),e.actionId(),applied.action()))));
    snapshots.append(new StateSnapshot(match.matchId(),version,applied.state(),clock.instant()));
    long sequence=match.gameEventSeq();
    for (var event:applied.events()) gameEvents.append(new GameEvent(match.matchId(),++sequence,version,e.decisionId(),event.type(),event.eventVersion(),event.payload(),clock.instant()));
    DecisionExecutionService.replaced(decisions.replace(decision,lifecycle.terminal(decision,DecisionStatus.COMPLETED,null)));
    DecisionExecutionService.replaced(turns.replace(turn,new TurnContext(turn.turnId(),turn.turnNo(),turn.roundNo(),turn.baseStateVersion(),turn.schedulingMode(),turn.requiredParticipants(),TurnStatus.RESOLVED)));
    if (next!=null) turns.insert(match.matchId(),next);
    Match updated=new Match(match.matchId(),match.roomId(),match.gameKey(),finished?MatchStatus.FINISHED:MatchStatus.ACTIVE,
        version,sequence,next==null?null:next.turnId(),match.participants(),applied.result());
    DecisionExecutionService.replaced(matches.replace(match,updated));
    DecisionRecord record=DecisionExecutionService.present(records.find(e.decisionId()),"DECISION_RECORD_NOT_FOUND");
    DecisionExecutionService.replaced(records.replace(record,new DecisionRecord(record.decisionId(),record.contextBuilderVersion(),record.modelInputJson(),record.modelOutputJson(),
        json.writeValueAsString(applied.action()),json.writeValueAsString(Map.of("status","ACCEPTED")),result,record.errorJson(),record.usage(),record.latency(),record.diagnosticsJson())));
    ExecutionStatus status=finished||room.executionStatus()==ExecutionStatus.PAUSING||room.executionMode()==ExecutionMode.STEP ? ExecutionStatus.PAUSED : ExecutionStatus.RUNNING;
    var roomEvents=new ArrayList<DecisionExecutionService.Event>();
    roomEvents.add(new DecisionExecutionService.Event("DECISION_FINISHED",Map.of("decisionId",e.decisionId().toString(),"status","COMPLETED")));
    roomEvents.add(new DecisionExecutionService.Event("ACTION_RESOLVED",Map.of("decisionId",e.decisionId().toString(),"stateVersion",version)));
    if (finished) roomEvents.add(new DecisionExecutionService.Event("MATCH_FINISHED",Map.of("result",json.convertValue(json.valueToTree(applied.result()),new TypeReference<Map<String,Object>>() {}))));
    lifecycle.roomEvents(room,roomEvents,status);
    return CompletableFuture.completedFuture(result);
  }
  private CompletionStage<ActionSubmissionResult> invalid(ActionEnvelope e,Match match,Decision d,String code) {
    lifecycle.fail(d.decisionId(),code);
    ActionSubmissionResult result=rejection(e,code,match.stateVersion()); receipts.saveFinal(e,result);
    DecisionRecord old=DecisionExecutionService.present(records.find(e.decisionId()),"DECISION_RECORD_NOT_FOUND");
    DecisionExecutionService.replaced(records.replace(old,new DecisionRecord(old.decisionId(),old.contextBuilderVersion(),old.modelInputJson(),old.modelOutputJson(),old.parsedActionJson(),
        json.writeValueAsString(Map.of("status","REJECTED","code",code)),result,old.errorJson(),old.usage(),old.latency(),old.diagnosticsJson())));
    return CompletableFuture.completedFuture(result);
  }
  private CompletionStage<ActionSubmissionResult> doneReject(ActionEnvelope e,String code,long version) {
    var result=rejection(e,code,version); receipts.saveFinal(e,result); return CompletableFuture.completedFuture(result);
  }
  private ActionSubmissionResult rejection(ActionEnvelope e,String code,long version) {
    return new ActionSubmissionResult(SubmissionStatus.REJECTED,e.actionId(),e.decisionId(),e.matchId(),version,null,code);
  }
}
