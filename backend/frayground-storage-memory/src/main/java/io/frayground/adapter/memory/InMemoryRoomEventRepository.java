package io.frayground.adapter.memory;
import io.frayground.application.port.*;
import io.frayground.domain.match.Match;
import io.frayground.domain.match.RoomEvent;
import java.util.*;
public final class InMemoryRoomEventRepository implements RoomEventRepository {
  private final InMemoryStorage storage;
  public InMemoryRoomEventRepository(InMemoryStorage storage) { this.storage=storage; }
  public void append(RoomEvent e) {
    MemoryState s=storage.writeState();
    if(!s.rooms.containsKey(e.roomId())) throw new StorageConflictException("Room does not exist");
    if(e.matchId()!=null) {
      Match match=s.matches.get(e.matchId());
      if(match==null||!match.roomId().equals(e.roomId())) throw new StorageConflictException("Match does not belong to room");
    }
    RoomEvent c=new RoomEvent(e.roomId(),e.sequence(),e.matchId(),e.roomRevision(),e.type(),MemoryValueCopy.map(e.payload()),e.occurredAt());
    String k=e.roomId()+":"+e.sequence();
    RoomEvent old=s.roomEvents.putIfAbsent(k,c);
    if(old!=null&&(!old.roomId().equals(c.roomId())||old.sequence()!=c.sequence()||!Objects.equals(old.matchId(),c.matchId())||old.roomRevision()!=c.roomRevision()||!old.type().equals(c.type())||!old.occurredAt().equals(c.occurredAt())||!MemoryValueCopy.equal(old.payload(),c.payload()))) throw new StorageConflictException("Event key conflict");
  }
  public List<RoomEvent> readAfter(UUID id,long seq,int limit) { if(seq<0||limit<=0)throw new IllegalArgumentException("cursor/limit"); return storage.readState().roomEvents.values().stream().filter(e->e.roomId().equals(id)&&e.sequence()>seq).sorted(Comparator.comparingLong(RoomEvent::sequence)).limit(limit).map(e->new RoomEvent(e.roomId(),e.sequence(),e.matchId(),e.roomRevision(),e.type(),MemoryValueCopy.map(e.payload()),e.occurredAt())).toList(); }
}
