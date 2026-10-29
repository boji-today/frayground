package io.frayground.application;

import io.frayground.agent.*;
import io.frayground.application.port.*;
import io.frayground.domain.action.*;
import io.frayground.domain.match.Room;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** CAS updates private model evidence without overwriting receipts or terminal errors. */
public class DecisionModelAuditService implements DecisionModelAudit {
  private final DecisionRecordStore records; private final DecisionStore decisions; private final MatchStore matches;
  private final RoomStore rooms; private final JsonMapper json;
  public DecisionModelAuditService(DecisionRecordStore records, DecisionStore decisions, MatchStore matches, RoomStore rooms, JsonMapper json) {
    this.records=records; this.decisions=decisions; this.matches=matches; this.rooms=rooms; this.json=json;
  }
  @Override @Transactional(transactionManager="gameTransactionManager")
  public boolean recordInput(UUID id, BuiltContext context, String schema, Map<String,Object> parameters) {
    Decision d=decisions.find(id).orElseThrow(); Room room=rooms.find(matches.find(d.matchId()).orElseThrow().roomId()).orElseThrow(); rooms.lock(room.roomId());
    DecisionRecord old=records.find(id).orElseThrow();
    if (d.status()!=DecisionStatus.RUNNING || old.modelInputJson()!=null) return false;
    var input=new LinkedHashMap<String,Object>(); input.put("messages",context.messages()); input.put("selectionSchema",schema); input.put("parameters",parameters);
    var next=copy(old,context.builderVersion(),json.writeValueAsString(input),old.modelOutputJson(),old.parsedActionJson(),old.errorJson(),old.usage(),old.latency(),old.diagnosticsJson());
    if (!records.replace(old,next)) throw new StorageConflictException("Model input audit CAS failed");
    return true;
  }
  @Override @Transactional(transactionManager="gameTransactionManager")
  public void recordOutput(UUID id, ModelInvocationResult result) {
    Decision d=decisions.find(id).orElseThrow(); Room room=rooms.find(matches.find(d.matchId()).orElseThrow().roomId()).orElseThrow(); rooms.lock(room.roomId());
    DecisionRecord old=records.find(id).orElseThrow();
    var normalized=new LinkedHashMap<String,Object>(); normalized.put("providerRequestId",result.providerRequestId()); normalized.put("actualModel",result.actualModel()); normalized.put("content",result.content()); normalized.put("toolArguments",result.toolArguments()); normalized.put("finishReason",result.finishReason());
    normalized.put("modelError",result.error()==null?null:Map.of("code",result.error().code(),"message",result.error().message()));
    String output=json.writeValueAsString(normalized);
    if (old.modelOutputJson()!=null) {
      if (old.modelOutputJson().equals(output) && Objects.equals(old.usage(),result.usage()) && Objects.equals(old.latency(),result.latency())) return;
      throw new StorageConflictException("Conflicting model output audit evidence");
    }
    boolean terminal=d.status()==DecisionStatus.COMPLETED || d.status()==DecisionStatus.FAILED
        || d.status()==DecisionStatus.EXPIRED || d.status()==DecisionStatus.CANCELLED;
    String error=terminal || old.errorJson()!=null ? old.errorJson()
        : result.error()==null?null:json.writeValueAsString(Map.of("code",result.error().code()));
    var next=copy(old,old.contextBuilderVersion(),old.modelInputJson(),output,old.parsedActionJson(),error,result.usage(),result.latency(),old.diagnosticsJson());
    if (!records.replace(old,next)) throw new StorageConflictException("Model output audit CAS failed");
  }
  private static DecisionRecord copy(DecisionRecord o,String version,String input,String output,String action,String error,TokenUsage usage,java.time.Duration latency,String diagnostics) {
    return new DecisionRecord(o.decisionId(),version,input,output,action,o.validationResultJson(),o.submissionResult(),error,usage,latency,diagnostics);
  }
}
