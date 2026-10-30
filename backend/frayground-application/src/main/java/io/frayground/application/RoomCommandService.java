package io.frayground.application;

import io.frayground.application.port.*;
import io.frayground.domain.action.*;
import io.frayground.domain.game.Participant;
import io.frayground.domain.match.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.transaction.annotation.Transactional;

/** Applies a command and its receipt atomically under the room lock. */
public class RoomCommandService {
  private final RoomStore rooms; private final MatchStore matches; private final TurnStore turns;
  private final DecisionStore decisions; private final SnapshotStore snapshots; private final CommandReceiptStore receipts;
  private final RoomEventRepository events; private final MatchFactory factory; private final DecisionExecutionService lifecycle;
  private final Clock clock; private final Supplier<UUID> ids;
  public RoomCommandService(RoomStore rooms, MatchStore matches, TurnStore turns, DecisionStore decisions,
      SnapshotStore snapshots, CommandReceiptStore receipts, RoomEventRepository events, MatchFactory factory,
      DecisionExecutionService lifecycle, Clock clock, Supplier<UUID> ids) {
    this.rooms=rooms; this.matches=matches; this.turns=turns; this.decisions=decisions; this.snapshots=snapshots;
    this.receipts=receipts; this.events=events; this.factory=factory; this.lifecycle=lifecycle; this.clock=clock; this.ids=ids;
  }
  record CommandExecution(CommandResult result, UUID tickMatch, UUID cancelDecision, UUID resumeDecision) {}
  @Transactional(transactionManager="gameTransactionManager")
  public CommandExecution execute(UUID roomId, ControlCommand command) {
    rooms.lock(roomId);
    Room room=DecisionExecutionService.present(rooms.find(roomId),"ROOM_NOT_FOUND");
    byte[] hash=hash(roomId,command);
    var prior=receipts.find(roomId,command.commandId());
    if (prior.isPresent()) {
      if (MessageDigest.isEqual(hash,receipts.findRequestHash(roomId,command.commandId()).orElseThrow())) return new CommandExecution(prior.get(),null,null,null);
      return new CommandExecution(result(room,command,"COMMAND_ID_CONFLICT"),null,null,null);
    }
    Match match=DecisionExecutionService.present(matches.find(room.activeMatchId()),"MATCH_NOT_FOUND");
    String error=null; UUID tick=null, cancel=null, resume=null;
    if (!Objects.equals(command.expectedMatchId(),room.activeMatchId())) error="STALE_MATCH";
    else if (command.expectedRoomRevision()!=room.revision()) error="ROOM_REVISION_CONFLICT";
    else if (room.health()!=RoomHealth.OK) error="ROOM_ERROR";
    else if (command.type()!=ControlCommandType.RESET && (match.status()==MatchStatus.FINISHED || match.status()==MatchStatus.ABORTED)) error="MATCH_NOT_ACTIVE";
    else {
      var active=active(match); boolean busy=room.executionStatus()!=ExecutionStatus.PAUSED || active.isPresent();
      ExecutionMode mode=room.executionMode(); ExecutionStatus status=room.executionStatus();
      switch (command.type()) {
        case START, CONTINUE, STEP -> {
          boolean recover=command.type()==ControlCommandType.CONTINUE && status==ExecutionStatus.PAUSED
              && match.status()==MatchStatus.ACTIVE && active.isPresent()
              && lifecycle.pendingOutputs().contains(active.get().decisionId());
          if (busy && !recover) error="CONTROL_BUSY";
          else if ((command.type()==ControlCommandType.START && match.status()!=MatchStatus.READY)
              || (command.type()==ControlCommandType.CONTINUE && match.status()!=MatchStatus.ACTIVE)
              || (command.type()==ControlCommandType.STEP && match.status()!=MatchStatus.READY && match.status()!=MatchStatus.ACTIVE)) error="MATCH_NOT_ACTIVE";
          else {
            if (match.status()==MatchStatus.READY) {
              Match next=new Match(match.matchId(),match.roomId(),match.gameKey(),MatchStatus.ACTIVE,match.stateVersion(),match.gameEventSeq(),match.currentTurnId(),match.participants(),match.result());
              DecisionExecutionService.replaced(matches.replace(match,next)); match=next;
            }
            mode=command.type()==ControlCommandType.STEP?ExecutionMode.STEP:ExecutionMode.AUTO;
            status=ExecutionStatus.RUNNING;
            if (recover) resume=active.orElseThrow().decisionId(); else tick=match.matchId();
          }
        }
        case PAUSE -> {
          if (match.status()!=MatchStatus.ACTIVE) error="MATCH_NOT_ACTIVE";
          else if (status!=ExecutionStatus.PAUSED) status=active.isPresent()?ExecutionStatus.PAUSING:ExecutionStatus.PAUSED;
        }
        case SET_MODE -> {
          mode=command.mode();
          if (mode==ExecutionMode.STEP && status!=ExecutionStatus.PAUSED) status=active.isPresent()?ExecutionStatus.PAUSING:ExecutionStatus.PAUSED;
        }
        case CANCEL_DECISION -> {
          if (active.isEmpty()) error="NO_ACTIVE_DECISION";
          else {
            cancel=active.get().decisionId(); lifecycle.cancel(roomId,command.commandId()); room=rooms.find(roomId).orElseThrow();
            mode=room.executionMode(); status=room.executionStatus();
            Room next=new Room(roomId,room.revision(),room.eventSeq()+1,room.activeMatchId(),mode,status,room.health(),room.healthError());
            DecisionExecutionService.replaced(rooms.replace(room,next));
            events.append(new RoomEvent(roomId,next.eventSeq(),next.activeMatchId(),next.revision(),"EXECUTION_CHANGED",Map.of("executionMode",mode.name(),"executionStatus",status.name()),clock.instant())); room=next;
          }
        }
        case RESET -> {
          if (active.isPresent()) { cancel=active.get().decisionId(); lifecycle.cancel(roomId,command.commandId()); room=rooms.find(roomId).orElseThrow(); }
          if (match.currentTurnId()!=null) turns.find(match.currentTurnId()).filter(t->t.status()==TurnStatus.OPEN).ifPresent(t->DecisionExecutionService.replaced(turns.replace(t,new TurnContext(t.turnId(),t.turnNo(),t.roundNo(),t.baseStateVersion(),t.schedulingMode(),t.requiredParticipants(),TurnStatus.CANCELLED))));
          if (match.status()!=MatchStatus.FINISHED && match.status()!=MatchStatus.ABORTED) DecisionExecutionService.replaced(matches.replace(match,new Match(match.matchId(),match.roomId(),match.gameKey(),MatchStatus.ABORTED,match.stateVersion(),match.gameEventSeq(),match.currentTurnId(),match.participants(),match.result())));
          List<Participant> participants=match.participants().stream().map(p->new Participant(ids.get(),p.agentId(),p.seatKey())).toList();
          InitializedMatch created=factory.create(roomId,match.gameKey(),participants);
          matches.insert(created.match()); if (created.firstTurn()!=null) turns.insert(created.match().matchId(),created.firstTurn()); snapshots.append(created.initialSnapshot());
          long revision=active.isPresent()?room.revision():room.revision()+1;
          Room next=new Room(roomId,revision,room.eventSeq()+1,created.match().matchId(),ExecutionMode.STEP,ExecutionStatus.PAUSED,room.health(),room.healthError());
          DecisionExecutionService.replaced(rooms.replace(room,next));
          events.append(new RoomEvent(roomId,next.eventSeq(),next.activeMatchId(),revision,"MATCH_RESET",Map.of("currentMatchId",next.activeMatchId().toString()),clock.instant())); room=next;
          mode=room.executionMode(); status=room.executionStatus();
        }
      }
      if (error==null && command.type()!=ControlCommandType.RESET && (mode!=room.executionMode() || status!=room.executionStatus() || tick!=null)) {
        Room next=new Room(roomId,room.revision()+1,room.eventSeq()+1,room.activeMatchId(),mode,status,room.health(),room.healthError());
        DecisionExecutionService.replaced(rooms.replace(room,next));
        events.append(new RoomEvent(roomId,next.eventSeq(),next.activeMatchId(),next.revision(),"EXECUTION_CHANGED",Map.of("executionMode",mode.name(),"executionStatus",status.name()),clock.instant())); room=next;
      }
    }
    CommandResult result=result(room,command,error); receipts.save(roomId,command.commandId(),hash,result);
    return new CommandExecution(result,tick,cancel,resume);
  }
  private Optional<Decision> active(Match match) {
    if (match.currentTurnId()==null) return Optional.empty();
    return decisions.findByTurn(match.currentTurnId()).stream().filter(d->d.status()==DecisionStatus.PENDING || d.status()==DecisionStatus.RUNNING).findFirst();
  }
  private static CommandResult result(Room room,ControlCommand command,String error) { return new CommandResult(command.commandId(),error,room.revision(),room.activeMatchId(),room.executionMode(),room.executionStatus()); }
  private static byte[] hash(UUID room,ControlCommand c) {
    try { return MessageDigest.getInstance("SHA-256").digest((room+"\n"+c.type()+"\n"+c.expectedRoomRevision()+"\n"+c.expectedMatchId()+"\n"+(c.mode()==null?"":c.mode().name())).getBytes(StandardCharsets.UTF_8)); }
    catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }
}
