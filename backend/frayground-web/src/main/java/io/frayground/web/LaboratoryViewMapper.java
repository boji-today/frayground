package io.frayground.web;

import io.frayground.application.port.LaboratoryPromptStore.AgentPromptState;
import io.frayground.application.query.LaboratoryQueryService.Unavailable;
import io.frayground.application.query.LaboratoryReadModels.*;
import io.frayground.domain.action.*;
import io.frayground.domain.game.*;
import io.frayground.domain.match.CommandResult;
import io.frayground.domain.strategy.PromptDraft;
import io.frayground.domain.strategy.PromptVersion;
import io.frayground.web.dto.LaboratoryDtos.*;
import java.util.*;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Explicit whitelist mapping, independent of storage and domain serialization. */
public final class LaboratoryViewMapper {
  private static final TypeReference<LinkedHashMap<String,Object>> OBJECT = new TypeReference<>() {};
  private final BoardProjector projector; private final JsonMapper json;
  public LaboratoryViewMapper(BoardProjector projector, JsonMapper json) { this.projector=projector; this.json=json; }
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
    return new GameEventView(e.matchId(),e.sequence(),e.stateVersion(),e.decisionId(),e.type(),e.eventVersion(),
        new LinkedHashMap<>(e.payload()),e.occurredAt());
  }
  public AgentView agent(AgentPromptState state) {
    return new AgentView(state.published().agentId(), state.name(), draft(state.draft()), prompt(state.published()),
        configuration(state));
  }
  public PromptDraftView draft(PromptDraft draft) {
    return new PromptDraftView(draft.agentId(), draft.revision(), draft.content(), draft.updatedAt());
  }
  public PromptVersionView prompt(PromptVersion version) {
    return new PromptVersionView(version.promptVersionId(), version.agentId(), version.versionNo(),
        version.sourceDraftRevision(), version.content(), version.createdAt());
  }
  public ObservationView observation(ObservationData data) {
    try {
      JsonNode node=json.readTree(data.observationJson());
      if(node==null || !node.isObject()) throw new Unavailable();
      return new ObservationView(data.matchId(), data.participantId(), data.observationVersion(), json.convertValue(node, OBJECT));
    } catch(RuntimeException ex) {
      if(ex instanceof Unavailable unavailable) throw unavailable;
      throw new Unavailable();
    }
  }
  private static AgentConfigurationView configuration(AgentPromptState state) {
    var config=state.configuration(); var model=config.model();
    return new AgentConfigurationView(config.configVersionId(), config.agentId(), config.versionNo(), config.runtimeType().name(),
        new ModelView(model.provider(), model.modelId(), model.thinking(), model.temperature(), model.maxOutputTokens(),
            model.decisionTimeoutMs(), model.outputStrategy(), model.allowBeta()),
        new MemoryPolicyView(config.memoryPolicy().mode().name()), config.createdAt());
  }
  private static Map<String,Object> action(Map<String,Object> a) {
    if(a==null || !(a.get("type") instanceof String type) || type.isBlank()) return null;
    return new LinkedHashMap<>(a);
  }
  private static String safeCode(String code) { return code!=null && code.matches("[A-Z][A-Z0-9_]{0,79}")?code:null; }
  private static ResultView result(GameResult r) {
    if(r==null)return null;
    var standings=r.standings().stream().map(s->new StandingView(s.participantId(),s.rank(),s.score())).toList();
    var winners=r.status()==GameOutcome.WIN?r.standings().stream().filter(s->s.rank()==1).map(ParticipantStanding::participantId).toList():List.<UUID>of();
    return new ResultView(r.status().name(),standings,r.reason(),winners);
  }
}
