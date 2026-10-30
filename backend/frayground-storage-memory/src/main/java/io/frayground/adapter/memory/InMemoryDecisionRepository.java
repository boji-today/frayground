package io.frayground.adapter.memory;
import io.frayground.application.port.*;
import io.frayground.domain.action.*;
import io.frayground.domain.match.TurnContext;
import java.util.*;
public final class InMemoryDecisionRepository implements DecisionStore {
  private final InMemoryStorage storage;
  public InMemoryDecisionRepository(InMemoryStorage storage) { this.storage=storage; }
  public Optional<Decision> find(UUID id) { return Optional.ofNullable(storage.readState().decisions.get(id)); }
  public List<Decision> findByTurn(UUID id) { return storage.readState().decisions.values().stream().filter(d->d.turnId().equals(id)).toList(); }
  public void insert(Decision d) {
    MemoryState s=storage.writeState();
    var match=s.matches.get(d.matchId());
    if(match==null||!s.turnMatches.containsKey(d.turnId())||!s.turnMatches.get(d.turnId()).equals(d.matchId())) throw new StorageConflictException("Decision owner does not exist");
    if(match.participants().stream().noneMatch(p->p.participantId().equals(d.participantId()))) throw new StorageConflictException("Decision participant does not belong to its match");
    TurnContext turn=s.turns.get(d.turnId());
    if(turn==null||!turn.requiredParticipants().contains(d.participantId())) throw new StorageConflictException("Decision participant is not required for its turn");
    if(d.stateVersion()!=turn.baseStateVersion()) throw new StorageConflictException("Decision state version does not match its turn");
    if(s.decisions.putIfAbsent(d.decisionId(),d)!=null) throw new StorageConflictException("Decision exists");
  }
  public boolean replace(Decision e,Decision r) {
    if(!e.decisionId().equals(r.decisionId())||!e.matchId().equals(r.matchId())||!e.turnId().equals(r.turnId())||!e.participantId().equals(r.participantId()))throw new IllegalArgumentException("decision identity or owner cannot change");
    if(e.status()!=r.status()&&!allowed(e.status(),r.status()))return false;
    MemoryState s=storage.writeState();
    if(!Objects.equals(s.decisions.get(e.decisionId()),e)) return false;
    TurnContext turn=s.turns.get(r.turnId());
    if(turn==null||!turn.requiredParticipants().contains(r.participantId())) throw new StorageConflictException("Decision participant is not required for its turn");
    if(r.stateVersion()!=turn.baseStateVersion()) throw new StorageConflictException("Decision state version does not match its turn");
    return s.decisions.replace(e.decisionId(),e,r);
  }
  public boolean transition(UUID id,DecisionStatus from,DecisionStatus to) { MemoryState s=storage.writeState(); Decision d=s.decisions.get(id); if(d==null||d.status()!=from||!allowed(from,to))return false; Decision n=new Decision(d.decisionId(),d.matchId(),d.turnId(),d.participantId(),to,d.stateVersion(),d.observationVersion(),d.promptVersionId(),d.configVersionId(),d.requestJson(),d.startedAt(),d.deadlineAt(),d.endedAt(),d.errorCode()); return s.decisions.replace(id,d,n); }
  private static boolean allowed(DecisionStatus f,DecisionStatus t) { return (f==DecisionStatus.PENDING&&(t==DecisionStatus.RUNNING||t==DecisionStatus.CANCELLED))||(f==DecisionStatus.RUNNING&&(t==DecisionStatus.COMPLETED||t==DecisionStatus.FAILED||t==DecisionStatus.EXPIRED||t==DecisionStatus.CANCELLED)); }
}
