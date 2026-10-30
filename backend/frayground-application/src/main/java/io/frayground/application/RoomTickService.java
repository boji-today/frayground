package io.frayground.application;

import io.frayground.application.port.*;
import io.frayground.domain.action.DecisionStatus;
import io.frayground.domain.match.*;
import io.frayground.engine.StartedDecision;
import java.time.Clock;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;

/** Starts at most one decision while holding the room lock. */
public class RoomTickService {
  private final RoomStore rooms; private final MatchStore matches; private final DecisionStore decisions;
  private final DecisionExecutionService lifecycle;
  private final RoomEventRepository events; private final Clock clock;
  public RoomTickService(RoomStore rooms, MatchStore matches, DecisionStore decisions, DecisionExecutionService lifecycle,RoomEventRepository events,Clock clock) {
    this.rooms=rooms; this.matches=matches; this.decisions=decisions; this.lifecycle=lifecycle;
    this.events=events; this.clock=clock;
  }
  static final class TickFailure extends RuntimeException {
    final Room room; final Match match;
    TickFailure(Room room,Match match,RuntimeException cause) { super(cause); this.room=room; this.match=match; }
  }
  @Transactional(transactionManager="gameTransactionManager")
  public Optional<StartedDecision> tick(UUID roomId, UUID expectedMatchId) {
    rooms.lock(roomId);
    Room room=rooms.find(roomId).orElse(null);
    if (room==null || room.health()!=RoomHealth.OK || room.executionStatus()!=ExecutionStatus.RUNNING
        || !Objects.equals(room.activeMatchId(),expectedMatchId)) return Optional.empty();
    Match match=matches.find(expectedMatchId).orElse(null);
    if (match==null || match.status()!=MatchStatus.ACTIVE) return Optional.empty();
    if (match.currentTurnId()!=null && decisions.findByTurn(match.currentTurnId()).stream()
        .anyMatch(d->d.status()==DecisionStatus.RUNNING || d.status()==DecisionStatus.PENDING)) return Optional.empty();
    Match original=match;
    try {
      lifecycle.prepare(roomId);
      match=matches.find(expectedMatchId).orElseThrow();
      if (match.status()!=MatchStatus.ACTIVE || match.currentTurnId()==null) return Optional.empty();
      return Optional.of(lifecycle.start(roomId));
    } catch (RuntimeException failure) { throw new TickFailure(room,original,failure); }
  }
  @Transactional(transactionManager="gameTransactionManager")
  public void pauseFailedTick(TickFailure failure) {
    Room expected=failure.room; rooms.lock(expected.roomId());
    Room room=rooms.find(expected.roomId()).orElse(null); Match match=matches.find(failure.match.matchId()).orElse(null);
    if (!expected.equals(room) || !failure.match.equals(match) || !Objects.equals(room.activeMatchId(),failure.match.matchId()) || room.health()!=RoomHealth.OK
        || room.executionStatus()!=ExecutionStatus.RUNNING || match.status()!=MatchStatus.ACTIVE) return;
    if (match.currentTurnId()!=null && decisions.findByTurn(match.currentTurnId()).stream()
        .anyMatch(d->d.status()==DecisionStatus.RUNNING || d.status()==DecisionStatus.PENDING)) return;
    pause(room,"DECISION_START_FAILED");
  }
  @Transactional(transactionManager="gameTransactionManager")
  public boolean pausePending(StartedDecision started) {
    rooms.lock(started.roomId()); Room room=rooms.find(started.roomId()).orElse(null);
    Match match=matches.find(started.matchId()).orElse(null); var decision=decisions.find(started.decisionId()).orElse(null);
    if (room==null || room.health()!=RoomHealth.OK || !Objects.equals(room.activeMatchId(),started.matchId())
        || (room.executionStatus()!=ExecutionStatus.RUNNING && room.executionStatus()!=ExecutionStatus.PAUSING)
        || match==null || match.status()!=MatchStatus.ACTIVE || !Objects.equals(match.currentTurnId(),started.turnId())
        || decision==null || decision.status()!=DecisionStatus.RUNNING || !lifecycle.pendingOutputs().contains(started.decisionId())) return false;
    pause(room,"DECISION_OUTPUT_PERSISTENCE_FAILED"); return true;
  }
  private void pause(Room room,String code) {
    Room next=new Room(room.roomId(),room.revision()+1,room.eventSeq()+1,room.activeMatchId(),room.executionMode(),ExecutionStatus.PAUSED,room.health(),room.healthError());
    DecisionExecutionService.replaced(rooms.replace(room,next));
    events.append(new RoomEvent(room.roomId(),next.eventSeq(),room.activeMatchId(),next.revision(),"EXECUTION_CHANGED",
        Map.of("executionMode",next.executionMode().name(),"executionStatus",next.executionStatus().name(),"errorCode",code),clock.instant()));
  }
  @Transactional(transactionManager="gameTransactionManager")
  public boolean finishIfCurrent(StartedDecision started, boolean expire) {
    rooms.lock(started.roomId());
    Room room=rooms.find(started.roomId()).orElse(null);
    Match match=matches.find(started.matchId()).orElse(null);
    var decision=decisions.find(started.decisionId()).orElse(null);
    if (room==null || room.health()!=RoomHealth.OK || !Objects.equals(room.activeMatchId(),started.matchId()) || match==null
        || !match.roomId().equals(started.roomId()) || match.status()!=MatchStatus.ACTIVE || !Objects.equals(match.currentTurnId(),started.turnId())
        || decision==null || (decision.status()!=DecisionStatus.RUNNING && decision.status()!=DecisionStatus.PENDING)) return false;
    if (expire) lifecycle.expire(started.decisionId());
    else lifecycle.fail(started.decisionId(),"RUNTIME_ERROR");
    return decisions.find(started.decisionId()).map(d->d.status()!=DecisionStatus.RUNNING && d.status()!=DecisionStatus.PENDING).orElse(false);
  }
}
