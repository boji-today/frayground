package io.frayground.web;

import io.frayground.application.query.LaboratoryReadModels.*;
import io.frayground.domain.action.*;
import io.frayground.domain.game.*;
import io.frayground.domain.match.CommandResult;
import io.frayground.web.dto.LaboratoryDtos.*;
import java.util.*;

/** Explicit whitelist mapping, independent of storage and domain serialization. */
public final class LaboratoryViewMapper {
  private final ConnectFourViewProjector projector;
  public LaboratoryViewMapper(ConnectFourViewProjector projector) { this.projector=projector; }
  public RoomView room(RoomData data) {
    var r=data.room();
    return new RoomView(r.roomId(),r.revision(),r.eventSeq(),r.executionMode().name(),
        r.executionStatus().name(),r.health().name(),r.healthError()==null?null:"ROOM_UNAVAILABLE",
        data.currentMatch()==null?null:match(data.currentMatch()));
  }
  public MatchView match(MatchData data) {
    var m=data.match(); var t=data.turn(); var result=result(m.result());
    var turn=t==null?new TurnView(null,null,null,null,List.of()):new TurnView(t.turnId(),t.turnNo(),t.roundNo(),
        t.requiredParticipants(),data.currentDecisions().stream().map(this::summary).toList());
    return new MatchView(m.matchId(),m.roomId(),m.gameKey().gameType(),m.gameKey().gameRulesVersion(),
        m.status().name(),m.stateVersion(),m.participants().stream()
          .map(p->new ParticipantView(p.participantId(),p.agentId(),p.seatKey())).toList(),turn,result,projector.project(data,result));
  }
  public CommandAckView ack(CommandResult c) {
    return new CommandAckView(c.commandId(),c.roomRevision(),c.matchId(),c.executionMode().name(),c.executionStatus().name());
  }
  public DecisionView decision(DecisionData data) {
    var d=data.decision(); var rec=data.record(); var sub=rec==null?null:rec.submissionResult();
    var receipt=sub==null?null:new ReceiptView(sub.status().name(),sub.actionId(),sub.stateVersion(),
        sub.status()==SubmissionStatus.EXECUTED?action(sub.executedAction()):null,safeCode(sub.errorCode()));
    var usage=rec==null||rec.usage()==null?null:new UsageView(rec.usage().inputTokens(),rec.usage().outputTokens(),
        rec.usage().cacheHitTokens(),rec.usage().cacheMissTokens());
    return new DecisionView(d.decisionId(),d.matchId(),d.turnId(),d.participantId(),d.status().name(),
        d.stateVersion(),d.observationVersion(),d.promptVersionId(),d.configVersionId(),d.startedAt(),d.deadlineAt(),d.endedAt(),
        safeCode(d.errorCode()),data.submitted()==null?null:action(data.submitted().action()),receipt,usage,
        rec==null||rec.latency()==null?null:rec.latency().toMillis());
  }
  public DecisionSummaryView summary(DecisionData data) {
    var d=decision(data);
    return new DecisionSummaryView(d.decisionId(),d.matchId(),d.turnId(),d.participantId(),d.status(),
        d.stateVersion(),d.observationVersion(),d.promptVersionId(),d.configVersionId(),d.startedAt(),
        d.deadlineAt(),d.endedAt(),d.errorCode(),d.usage(),d.latencyMs());
  }
  public GameEventView event(GameEvent e) {
    Map<String,Object> payload=new LinkedHashMap<>();
    if(e.type().equals("DiscPlaced")) for(String key:List.of("row","column","seat","participantId"))
      if(e.payload().containsKey(key))payload.put(key,e.payload().get(key));
    return new GameEventView(e.matchId(),e.sequence(),e.stateVersion(),e.decisionId(),e.type(),e.eventVersion(),Map.copyOf(payload),e.occurredAt());
  }
  private static ActionView action(Map<String,Object> a) {
    if(a==null)return null;
    Object type=a.get("type"), column=a.get("column");
    if(!"place_disc".equals(type)||!(column instanceof Number n))return null;
    return new ActionView("place_disc",n.intValue());
  }
  private static String safeCode(String code) { return code!=null && code.matches("[A-Z][A-Z0-9_]{0,79}")?code:null; }
  private static ResultView result(GameResult r) {
    if(r==null)return null;
    var standings=r.standings().stream().map(s->new StandingView(s.participantId(),s.rank(),s.score())).toList();
    var winners=r.status()==GameOutcome.WIN?r.standings().stream().filter(s->s.rank()==1).map(ParticipantStanding::participantId).toList():List.<UUID>of();
    return new ResultView(r.status().name(),standings,r.reason(),winners);
  }
}
