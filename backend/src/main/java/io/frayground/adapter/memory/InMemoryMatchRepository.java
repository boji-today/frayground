package io.frayground.adapter.memory;
import io.frayground.application.port.*;
import io.frayground.domain.match.Match;
import java.util.*;
public final class InMemoryMatchRepository implements MatchStore {
  private final InMemoryStorage storage;
  public InMemoryMatchRepository(InMemoryStorage storage) { this.storage=storage; }
  public Optional<Match> find(UUID id) { return Optional.ofNullable(storage.readState().matches.get(id)); }
  public void insert(Match m) { MemoryState s=storage.writeState(); if(!s.rooms.containsKey(m.roomId())) throw new StorageConflictException("Owning room does not exist"); if(s.matches.putIfAbsent(m.matchId(),m)!=null) throw new StorageConflictException("Match already exists"); }
  public boolean replace(Match e,Match r) {
    if(!e.matchId().equals(r.matchId())||!e.roomId().equals(r.roomId())) throw new IllegalArgumentException("match identity or owner cannot change");
    MemoryState s=storage.writeState();
    if(r.currentTurnId()!=null&&!r.matchId().equals(s.turnMatches.get(r.currentTurnId()))) throw new StorageConflictException("Current turn does not belong to match");
    return s.matches.replace(e.matchId(),e,r);
  }
  public boolean compareAndSetVersion(UUID id,long expected,long next) { MemoryState s=storage.writeState(); Match m=s.matches.get(id); if(m==null||m.stateVersion()!=expected||next!=expected+1)return false; return s.matches.replace(id,m,new Match(m.matchId(),m.roomId(),m.gameKey(),m.status(),next,m.gameEventSeq(),m.currentTurnId(),m.participants(),m.result())); }
}
