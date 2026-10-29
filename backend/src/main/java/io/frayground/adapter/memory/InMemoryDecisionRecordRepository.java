package io.frayground.adapter.memory;
import io.frayground.application.port.*;
import io.frayground.domain.action.ActionSubmissionResult;
import io.frayground.domain.action.Decision;
import io.frayground.domain.action.DecisionRecord;
import java.util.*;
public final class InMemoryDecisionRecordRepository implements DecisionRecordStore {
  private final InMemoryStorage storage;
  public InMemoryDecisionRecordRepository(InMemoryStorage storage) { this.storage=storage; }
  public Optional<DecisionRecord> find(UUID id) { DecisionRecord d=storage.readState().records.get(id); return d==null?Optional.empty():Optional.of(copy(d)); }
  public void insert(DecisionRecord d) {
    MemoryState s=storage.writeState();
    Decision decision=s.decisions.get(d.decisionId());
    if(decision==null) throw new StorageConflictException("Decision does not exist");
    requireReceipt(d,decision);
    if(s.records.putIfAbsent(d.decisionId(),copy(d))!=null) throw new StorageConflictException("Decision record exists");
  }
  public boolean replace(DecisionRecord e,DecisionRecord r) {
    if(!e.decisionId().equals(r.decisionId())) throw new IllegalArgumentException("decision identity cannot change");
    MemoryState s=storage.writeState();
    Decision decision=s.decisions.get(r.decisionId());
    if(decision==null) throw new StorageConflictException("Decision does not exist");
    requireReceipt(r,decision);
    return s.records.replace(e.decisionId(),e,copy(r));
  }
  private static void requireReceipt(DecisionRecord record,Decision decision) {
    ActionSubmissionResult result=record.submissionResult();
    if(result!=null&&(!result.decisionId().equals(record.decisionId())||!result.matchId().equals(decision.matchId()))) throw new StorageConflictException("Decision record receipt does not match its decision");
  }
  private static DecisionRecord copy(DecisionRecord d) {
    ActionSubmissionResult r=d.submissionResult();
    ActionSubmissionResult rc=r==null?null:new ActionSubmissionResult(r.status(),r.actionId(),r.decisionId(),r.matchId(),r.stateVersion(),r.executedAction()==null?null:MemoryValueCopy.map(r.executedAction()),r.errorCode());
    return new DecisionRecord(d.decisionId(),d.contextBuilderVersion(),d.modelInputJson(),d.modelOutputJson(),d.parsedActionJson(),d.validationResultJson(),rc,d.errorJson(),d.usage(),d.latency(),d.diagnosticsJson());
  }
}
