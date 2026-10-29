package io.frayground.application;

import io.frayground.agent.*;
import io.frayground.application.port.*;
import io.frayground.domain.action.*;
import io.frayground.domain.game.*;
import io.frayground.domain.match.*;
import io.frayground.engine.*;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Transactional decision boundaries. Runtime execution occurs outside these methods. */
public class DecisionExecutionService implements DecisionLifecycle {
  private final RoomStore rooms; private final MatchStore matches; private final TurnStore turns;
  private final DecisionStore decisions; private final DecisionRecordStore records;
  private final SnapshotStore snapshots; private final DecisionInputSnapshotStore inputs;
  private final DecisionExecutionClaimStore claims;
  private final PendingDecisionOutputStore pending;
  private final RoomEventRepository events; private final AgentBindingsReader bindings;
  private final DecisionGameAccess games; private final Clock clock; private final Supplier<UUID> ids;
  private final JsonMapper json;
  private final DecisionModelAudit modelAudit;
  public DecisionExecutionService(RoomStore rooms, MatchStore matches, TurnStore turns,
      DecisionStore decisions, DecisionRecordStore records, SnapshotStore snapshots,
      DecisionInputSnapshotStore inputs, DecisionExecutionClaimStore claims, PendingDecisionOutputStore pending, RoomEventRepository events, AgentBindingsReader bindings,
      DecisionGameAccess games, Clock clock, Supplier<UUID> ids, JsonMapper json, DecisionModelAudit modelAudit) {
    this.rooms=rooms; this.matches=matches; this.turns=turns; this.decisions=decisions; this.records=records;
    this.snapshots=snapshots; this.inputs=inputs; this.claims=claims; this.pending=pending; this.events=events; this.bindings=bindings;
    this.games=games; this.clock=clock; this.ids=ids; this.json=json; this.modelAudit=modelAudit;
  }
  static void require(boolean value, String code) { if (!value) throw new IllegalStateException(code); }
  static <T> T present(Optional<T> value, String code) { return value.orElseThrow(() -> new IllegalStateException(code)); }
  static void replaced(boolean value) { if (!value) throw new StorageConflictException("Concurrent replacement failed"); }
  @Override @Transactional(transactionManager="gameTransactionManager")
  public StartedDecision start(UUID roomId) {
    rooms.lock(roomId);
    Room room=present(rooms.find(roomId),"ROOM_NOT_FOUND");
    require(room.health()==RoomHealth.OK,"ROOM_ERROR");
    require(room.executionStatus()==ExecutionStatus.RUNNING,"ROOM_NOT_RUNNING");
    Match match=present(matches.find(room.activeMatchId()),"MATCH_NOT_FOUND");
    require(match.roomId().equals(roomId),"MATCH_ROOM_MISMATCH");
    require(match.status()==MatchStatus.ACTIVE,"MATCH_NOT_ACTIVE");
    TurnContext turn=present(turns.find(match.currentTurnId()),"TURN_NOT_FOUND");
    if (turn.status()==TurnStatus.CANCELLED) {
      TurnContext next=new TurnContext(ids.get(),turn.turnNo(),turn.roundNo(),turn.baseStateVersion(),
          turn.schedulingMode(),turn.requiredParticipants(),TurnStatus.OPEN);
      turns.insert(match.matchId(),next);
      Match changed=new Match(match.matchId(),match.roomId(),match.gameKey(),match.status(),match.stateVersion(),
          match.gameEventSeq(),next.turnId(),match.participants(),match.result());
      replaced(matches.replace(match,changed)); match=changed; turn=next;
    }
    require(turn.status()==TurnStatus.OPEN,"TURN_NOT_OPEN");
    require(turn.baseStateVersion()==match.stateVersion(),"STATE_VERSION_MISMATCH");
    require(turn.schedulingMode()==SchedulingMode.TURN_BASED_SEQUENTIAL,"SCHEDULING_MODE_NOT_SUPPORTED");
    require(decisions.findByTurn(turn.turnId()).stream().noneMatch(d->d.status()==DecisionStatus.PENDING||d.status()==DecisionStatus.RUNNING),"DECISION_ALREADY_RUNNING");
    var snapshot=present(snapshots.find(match.matchId(),match.stateVersion()),"SNAPSHOT_NOT_FOUND");
    UUID participantId=turn.requiredParticipants().get(0);
    var participant=match.participants().stream().filter(p->p.participantId().equals(participantId)).findFirst().orElseThrow();
    HostedBindings bound=present(bindings.current(participant.agentId()),"BINDINGS_NOT_FOUND");
    require(bound.prompt().agentId().equals(participant.agentId())&&bound.configuration().agentId().equals(participant.agentId()),"BINDINGS_AGENT_MISMATCH");
    var prepared=games.prepare(match.gameKey(),match.stateVersion(),snapshot.state(),match.participants(),participantId);
    require(prepared.plan().mode()==SchedulingMode.TURN_BASED_SEQUENTIAL,"SCHEDULING_MODE_NOT_SUPPORTED");
    require(prepared.plan().requiredParticipants().equals(turn.requiredParticipants()),"TURN_PLAN_MISMATCH");
    var now=clock.instant(); var deadline=now.plusMillis(bound.configuration().model().decisionTimeoutMs()); UUID id=ids.get();
    var started=new StartedDecision(roomId,match.matchId(),id,participantId,turn.turnId(),bound.prompt().promptVersionId(),
        bound.configuration().configVersionId(),match.stateVersion(),match.stateVersion(),prepared.observationJson(),prepared.actionSpecificationJson(),deadline);
    var request=new DecisionRequest("1.0",id,match.matchId(),participantId,turn.turnId(),match.stateVersion(),match.stateVersion(),
        match.gameKey().gameType(),match.gameKey().gameRulesVersion(),prepared.schemaId(),prepared.schemaJson(),prepared.actionSpecificationJson(),prepared.rulesText(),prepared.observationJson(),deadline,null);
    decisions.insert(new Decision(id,match.matchId(),turn.turnId(),participantId,DecisionStatus.RUNNING,match.stateVersion(),match.stateVersion(),
        bound.prompt().promptVersionId(),bound.configuration().configVersionId(),null,now,deadline,null,null));
    inputs.save(new DecisionInputSnapshot(started,request,bound));
    records.insert(new DecisionRecord(id,null,null,null,null,null,null,null,null,null,null));
    roomEvent(room,"DECISION_STARTED",Map.of("decisionId",id.toString(),"participantId",participantId.toString(),"promptVersionId",bound.prompt().promptVersionId().toString()),room.executionStatus());
    return started;
  }
  public RuntimeInput runtimeInput(UUID decisionId) { return present(inputs.find(decisionId),"DECISION_INPUT_NOT_FOUND").runtimeInput(); }
  public PendingDecisionOutputStore pendingOutputs() { return pending; }
  public DecisionModelAudit modelAudit() { return modelAudit; }
  @Transactional(transactionManager="gameTransactionManager")
  public String claimExecution(UUID id,boolean cancelled) {
    Decision d=lockDecision(id);
    if (d.status()!=DecisionStatus.RUNNING || !isCurrent(d)) return "DECISION_NOT_RUNNING";
    if (!clock.instant().isBefore(d.deadlineAt())) { finish(id,DecisionStatus.EXPIRED,"DECISION_EXPIRED",true); return "DECISION_EXPIRED"; }
    if (cancelled) { finish(id,DecisionStatus.CANCELLED,"DECISION_CANCELLED",false); return "DECISION_CANCELLED"; }
    return claims.claim(id) ? null : "DECISION_ALREADY_CLAIMED";
  }
  @Transactional(transactionManager="gameTransactionManager")
  public boolean saveExecutionEnvelope(ActionEnvelope envelope) {
    Decision d=lockDecision(envelope.decisionId());
    if (d.status()!=DecisionStatus.RUNNING || !isCurrent(d)) return false;
    if (!clock.instant().isBefore(d.deadlineAt())) { finish(d.decisionId(),DecisionStatus.EXPIRED,"DECISION_EXPIRED",true); return false; }
    claims.saveEnvelope(envelope); return true;
  }
  public Optional<ActionEnvelope> executionEnvelope(UUID id) { return claims.findEnvelope(id); }
  @Transactional(transactionManager="gameTransactionManager")
  public boolean recordCandidate(UUID decisionId, RuntimeOutcome candidate, String actionJson) {
    Decision d=lockDecision(decisionId); DecisionRecord old=present(records.find(decisionId),"DECISION_RECORD_NOT_FOUND");
    if (d.status()!=DecisionStatus.RUNNING || !isCurrent(d)) { appendDiagnostic(old,"LATE_RUNTIME_RESULT"); return false; }
    if (!clock.instant().isBefore(d.deadlineAt())) {
      finish(decisionId,DecisionStatus.EXPIRED,"DECISION_EXPIRED",true);
      appendDiagnostic(present(records.find(decisionId),"DECISION_RECORD_NOT_FOUND"),"LATE_RUNTIME_RESULT");
      return false;
    }
    String diagnostic=json.writeValueAsString(List.of(candidate instanceof RuntimeOutcome.SelectedAction selected
        ? "SELECTED_ACTION:"+selected.selectedActionId() : "RUNTIME_CANDIDATE"));
    var replacement=new DecisionRecord(old.decisionId(),old.contextBuilderVersion(),old.modelInputJson(),old.modelOutputJson(),actionJson,
        old.validationResultJson(),old.submissionResult(),old.errorJson(),old.usage(),old.latency(),diagnostic);
    replaced(records.replace(old,replacement));
    return true;
  }
  @Transactional(transactionManager="gameTransactionManager") public RuntimeError runtimeFailed(UUID id,String code) {
    Decision d=lockDecision(id);
    String originalCode=code;
    boolean active=d.status()==DecisionStatus.RUNNING && isCurrent(d);
    if ("DECISION_EXPIRED".equals(code) && d.status()==DecisionStatus.RUNNING && clock.instant().isBefore(d.deadlineAt()))
      code="RUNTIME_ERROR";
    finish(id,"DECISION_CANCELLED".equals(code)?DecisionStatus.CANCELLED:DecisionStatus.FAILED,code,false);
    Decision finalDecision=present(decisions.find(id),"DECISION_NOT_FOUND");
    String authoritative=finalDecision.errorCode();
    if (authoritative==null || finalDecision.status()==DecisionStatus.RUNNING || finalDecision.status()==DecisionStatus.PENDING)
      authoritative="DECISION_NOT_RUNNING";
    if (active && !originalCode.equals(authoritative))
      appendDiagnostic(present(records.find(id),"DECISION_RECORD_NOT_FOUND"),"RUNTIME_ERROR:"+originalCode);
    return new RuntimeError(authoritative,authoritative);
  }
  @Override @Transactional(transactionManager="gameTransactionManager") public void fail(UUID id,String code) { finish(id,DecisionStatus.FAILED,code,false); }
  @Override @Transactional(transactionManager="gameTransactionManager") public void expire(UUID id) { finish(id,DecisionStatus.EXPIRED,"DECISION_EXPIRED",true); }
  private Decision lockDecision(UUID id) {
    Decision d=present(decisions.find(id),"DECISION_NOT_FOUND");
    Match m=present(matches.find(d.matchId()),"MATCH_NOT_FOUND"); rooms.lock(m.roomId());
    return present(decisions.find(id),"DECISION_NOT_FOUND");
  }
  private boolean isCurrent(Decision d) {
    Match match=present(matches.find(d.matchId()),"MATCH_NOT_FOUND");
    Room room=present(rooms.find(match.roomId()),"ROOM_NOT_FOUND");
    return Objects.equals(room.activeMatchId(),match.matchId()) && Objects.equals(match.currentTurnId(),d.turnId())
        && match.status()==MatchStatus.ACTIVE && turns.find(d.turnId()).map(t->t.status()==TurnStatus.OPEN).orElse(false);
  }
  private void finish(UUID id,DecisionStatus status,String code,boolean checkDeadline) {
    Decision d=lockDecision(id); DecisionRecord record=present(records.find(id),"DECISION_RECORD_NOT_FOUND");
    if ((d.status()!=DecisionStatus.RUNNING && d.status()!=DecisionStatus.PENDING) || !isCurrent(d)) { appendDiagnostic(record,"LATE_"+code); return; }
    if (checkDeadline && clock.instant().isBefore(d.deadlineAt())) return;
    if (d.status()==DecisionStatus.RUNNING && !clock.instant().isBefore(d.deadlineAt())) { status=DecisionStatus.EXPIRED; code="DECISION_EXPIRED"; }
    if (d.status()==DecisionStatus.PENDING && status!=DecisionStatus.CANCELLED) return;
    replaced(decisions.replace(d,terminal(d,status,code)));
    if (status==DecisionStatus.CANCELLED) {
      TurnContext turn=present(turns.find(d.turnId()),"TURN_NOT_FOUND");
      replaced(turns.replace(turn,new TurnContext(turn.turnId(),turn.turnNo(),turn.roundNo(),turn.baseStateVersion(),turn.schedulingMode(),turn.requiredParticipants(),TurnStatus.CANCELLED)));
    }
    replaced(records.replace(record,errorRecord(record,code)));
    Match match=present(matches.find(d.matchId()),"MATCH_NOT_FOUND"); Room room=present(rooms.find(match.roomId()),"ROOM_NOT_FOUND");
    if (Objects.equals(room.activeMatchId(),match.matchId()) && Objects.equals(match.currentTurnId(),d.turnId()))
      roomEvent(room,"DECISION_FINISHED",Map.of("decisionId",id.toString(),"status",status.name(),"errorCode",code),ExecutionStatus.PAUSED);
  }
  Decision terminal(Decision d,DecisionStatus status,String code) {
    return new Decision(d.decisionId(),d.matchId(),d.turnId(),d.participantId(),status,d.stateVersion(),d.observationVersion(),
        d.promptVersionId(),d.configVersionId(),d.requestJson(),d.startedAt(),d.deadlineAt(),clock.instant(),code);
  }
  private DecisionRecord errorRecord(DecisionRecord record,String code) {
    return new DecisionRecord(record.decisionId(),record.contextBuilderVersion(),record.modelInputJson(),record.modelOutputJson(),
        record.parsedActionJson(),record.validationResultJson(),record.submissionResult(),json.writeValueAsString(Map.of("code",code)),record.usage(),record.latency(),record.diagnosticsJson());
  }
  void appendDiagnostic(DecisionRecord old,String text) {
    String diagnostics=old.diagnosticsJson()==null ? json.writeValueAsString(List.of(text)) : appendJson(old.diagnosticsJson(),text);
    replaced(records.replace(old,new DecisionRecord(old.decisionId(),old.contextBuilderVersion(),old.modelInputJson(),old.modelOutputJson(),
        old.parsedActionJson(),old.validationResultJson(),old.submissionResult(),old.errorJson(),old.usage(),old.latency(),diagnostics)));
  }
  private String appendJson(String old,String text) {
    var array=json.readTree(old); var values=new ArrayList<String>(); array.forEach(n->values.add(n.asString())); values.add(text); return json.writeValueAsString(values);
  }
  void roomEvent(Room room,String type,Map<String,Object> payload,ExecutionStatus status) {
    roomEvents(room,List.of(new Event(type,payload)),status);
  }
  record Event(String type,Map<String,Object> payload) {}
  void roomEvents(Room room,List<Event> pending,ExecutionStatus status) {
    Room next=new Room(room.roomId(),room.revision()+1,room.eventSeq()+pending.size(),room.activeMatchId(),room.executionMode(),status,room.health(),room.healthError());
    replaced(rooms.replace(room,next)); long sequence=room.eventSeq();
    for (var event:pending) events.append(new RoomEvent(room.roomId(),++sequence,room.activeMatchId(),next.revision(),event.type(),event.payload(),clock.instant()));
  }
  @Override @Transactional(transactionManager="gameTransactionManager") public void cancel(UUID roomId,UUID commandId) {
    rooms.lock(roomId); Room room=present(rooms.find(roomId),"ROOM_NOT_FOUND");
    if (room.activeMatchId()==null) return;
    Match match=present(matches.find(room.activeMatchId()),"MATCH_NOT_FOUND");
    require(match.roomId().equals(roomId),"MATCH_ROOM_MISMATCH");
    if (match.status()!=MatchStatus.ACTIVE || match.currentTurnId()==null) return;
    TurnContext turn=present(turns.find(match.currentTurnId()),"TURN_NOT_FOUND");
    if (turn.status()!=TurnStatus.OPEN) return;
    var active=decisions.findByTurn(turn.turnId()).stream().filter(d->d.status()==DecisionStatus.RUNNING||d.status()==DecisionStatus.PENDING).toList();
    if (active.isEmpty()) return;
    var finished=new ArrayList<Event>();
    boolean expired=false;
    for (var d:active) {
      require(d.matchId().equals(match.matchId()),"DECISION_MATCH_MISMATCH");
      boolean late=d.status()==DecisionStatus.RUNNING && !clock.instant().isBefore(d.deadlineAt());
      DecisionStatus outcome=late?DecisionStatus.EXPIRED:DecisionStatus.CANCELLED;
      String code=late?"DECISION_EXPIRED":"DECISION_CANCELLED";
      replaced(decisions.replace(d,terminal(d,outcome,code))); expired|=late;
      DecisionRecord record=present(records.find(d.decisionId()),"DECISION_RECORD_NOT_FOUND");
      replaced(records.replace(record,errorRecord(record,code)));
      finished.add(new Event("DECISION_FINISHED",Map.of("decisionId",d.decisionId().toString(),"status",outcome.name(),"errorCode",code)));
    }
    if (!expired) replaced(turns.replace(turn,new TurnContext(turn.turnId(),turn.turnNo(),turn.roundNo(),turn.baseStateVersion(),turn.schedulingMode(),turn.requiredParticipants(),TurnStatus.CANCELLED)));
    roomEvents(room,finished,ExecutionStatus.PAUSED);
  }
}
