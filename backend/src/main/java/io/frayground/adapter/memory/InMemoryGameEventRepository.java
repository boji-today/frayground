package io.frayground.adapter.memory;
import io.frayground.application.port.*;
import io.frayground.domain.action.Decision;
import io.frayground.domain.game.GameEvent;
import java.util.*;
public final class InMemoryGameEventRepository implements GameEventStore {
  private final InMemoryStorage storage;
  public InMemoryGameEventRepository(InMemoryStorage storage) { this.storage=storage; }
  public void append(GameEvent e) {
    MemoryState s=storage.writeState();
    if(!s.matches.containsKey(e.matchId())) throw new StorageConflictException("Match does not exist");
    if(e.decisionId()!=null) {
      Decision decision=s.decisions.get(e.decisionId());
      if(decision==null||!decision.matchId().equals(e.matchId())) throw new StorageConflictException("Decision does not belong to match");
    }
    GameEvent c=new GameEvent(e.matchId(),e.sequence(),e.stateVersion(),e.decisionId(),e.type(),e.eventVersion(),MemoryValueCopy.map(e.payload()),e.occurredAt());
    String k=e.matchId()+":"+e.sequence();
    GameEvent old=s.gameEvents.putIfAbsent(k,c);
    if(old!=null&&(!old.matchId().equals(c.matchId())||old.sequence()!=c.sequence()||old.stateVersion()!=c.stateVersion()||!Objects.equals(old.decisionId(),c.decisionId())||!old.type().equals(c.type())||old.eventVersion()!=c.eventVersion()||!old.occurredAt().equals(c.occurredAt())||!MemoryValueCopy.equal(old.payload(),c.payload()))) throw new StorageConflictException("Event key conflict");
  }
  public List<GameEvent> readAfter(UUID id,long seq,int limit) { if(seq<0||limit<=0)throw new IllegalArgumentException("cursor/limit"); return storage.readState().gameEvents.values().stream().filter(e->e.matchId().equals(id)&&e.sequence()>seq).sorted(Comparator.comparingLong(GameEvent::sequence)).limit(limit).map(e->new GameEvent(e.matchId(),e.sequence(),e.stateVersion(),e.decisionId(),e.type(),e.eventVersion(),MemoryValueCopy.map(e.payload()),e.occurredAt())).toList(); }
}
