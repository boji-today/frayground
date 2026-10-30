package io.frayground.adapter.memory;
import io.frayground.application.port.*;
import io.frayground.domain.game.StateSnapshot;
import java.util.*;
public final class InMemorySnapshotStore implements SnapshotStore {
  private final InMemoryStorage storage;
  public InMemorySnapshotStore(InMemoryStorage storage) { this.storage=storage; }
  public void append(StateSnapshot v) { MemoryState s=storage.writeState(); if(!s.matches.containsKey(v.matchId()))throw new StorageConflictException("Match does not exist"); StateSnapshot copy=new StateSnapshot(v.matchId(),v.stateVersion(),MemoryValueCopy.map(v.state()),v.createdAt()); String k=v.matchId()+":"+v.stateVersion(); StateSnapshot old=s.snapshots.putIfAbsent(k,copy); if(old!=null&&(!old.matchId().equals(copy.matchId())||old.stateVersion()!=copy.stateVersion()||!old.createdAt().equals(copy.createdAt())||!MemoryValueCopy.equal(old.state(),copy.state())))throw new StorageConflictException("Snapshot key conflict"); }
  public Optional<StateSnapshot> find(UUID match,long version) { StateSnapshot v=storage.readState().snapshots.get(match+":"+version); return v==null?Optional.empty():Optional.of(new StateSnapshot(v.matchId(),v.stateVersion(),MemoryValueCopy.map(v.state()),v.createdAt())); }
}
