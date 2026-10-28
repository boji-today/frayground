package io.frayground.adapter.memory;
import io.frayground.application.port.*;
import io.frayground.domain.action.Settlement;
import java.util.*;
public final class InMemorySettlementRepository implements SettlementStore {
  private final InMemoryStorage storage;
  public InMemorySettlementRepository(InMemoryStorage storage) { this.storage=storage; }
  public void save(Settlement v) { MemoryState s=storage.writeState(); if(!s.matches.containsKey(v.matchId())||!s.turnMatches.containsKey(v.turnId())||!v.matchId().equals(s.turnMatches.get(v.turnId())))throw new StorageConflictException("Settlement owner does not exist"); for(var action:v.actions()) { var decision=s.decisions.get(action.decisionId()); if(decision==null||!decision.matchId().equals(v.matchId())||!decision.turnId().equals(v.turnId()))throw new StorageConflictException("Settlement decision owner does not match"); } Settlement old=s.settlements.get(v.settlementId()); if(old!=null) { if(!same(old,v))throw new StorageConflictException("Settlement id conflict"); return; } if(s.settlements.values().stream().anyMatch(x->x.turnId().equals(v.turnId())||x.actions().stream().anyMatch(a->v.actions().stream().anyMatch(b->a.decisionId().equals(b.decisionId())||a.actionId().equals(b.actionId())))))throw new StorageConflictException("Turn, decision, or action already settled"); s.settlements.put(v.settlementId(),copy(v)); }
  public Optional<Settlement> findBySettlementId(UUID id) { Settlement v=storage.readState().settlements.get(id); return v==null?Optional.empty():Optional.of(copy(v)); }
  public Optional<Settlement> findByTurnId(UUID id) { return storage.readState().settlements.values().stream().filter(v->v.turnId().equals(id)).findFirst().map(InMemorySettlementRepository::copy); }
  private static Settlement copy(Settlement v) { return new Settlement(v.settlementId(),v.matchId(),v.turnId(),v.fromVersion(),v.toVersion(),v.actions().stream().map(a->new io.frayground.domain.action.SettledAction(a.participantId(),a.decisionId(),a.actionId(),MemoryValueCopy.map(a.action()))).toList()); }
  private static boolean same(Settlement a,Settlement b) {
    if(!a.settlementId().equals(b.settlementId())||!a.matchId().equals(b.matchId())||!a.turnId().equals(b.turnId())||a.fromVersion()!=b.fromVersion()||a.toVersion()!=b.toVersion()||a.actions().size()!=b.actions().size())return false;
    for(int i=0;i<a.actions().size();i++){var x=a.actions().get(i);var y=b.actions().get(i);if(!x.participantId().equals(y.participantId())||!x.decisionId().equals(y.decisionId())||!x.actionId().equals(y.actionId())||!MemoryValueCopy.equal(x.action(),y.action()))return false;}
    return true;
  }
}
