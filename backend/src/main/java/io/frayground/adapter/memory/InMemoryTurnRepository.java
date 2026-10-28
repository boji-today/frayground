package io.frayground.adapter.memory;
import io.frayground.application.port.*;
import io.frayground.domain.match.*;
import java.util.*;
public final class InMemoryTurnRepository implements TurnStore {
  private final InMemoryStorage storage;
  public InMemoryTurnRepository(InMemoryStorage storage) { this.storage=storage; }
  public void insert(UUID matchId,TurnContext turn) { MemoryState s=storage.writeState(); if(!s.matches.containsKey(matchId))throw new StorageConflictException("Owning match does not exist"); if(s.turns.putIfAbsent(turn.turnId(),turn)!=null)throw new StorageConflictException("Turn exists"); s.turnMatches.put(turn.turnId(),matchId); }
  public Optional<TurnContext> find(UUID id) { return Optional.ofNullable(storage.readState().turns.get(id)); }
  public boolean replace(TurnContext e,TurnContext r) { if(!e.turnId().equals(r.turnId()))throw new IllegalArgumentException("turn identity cannot change"); MemoryState s=storage.writeState(); if(!Objects.equals(s.turnMatches.get(e.turnId()),s.turnMatches.get(r.turnId())))throw new IllegalArgumentException("turn owner cannot change"); return s.turns.replace(e.turnId(),e,r); }
}
