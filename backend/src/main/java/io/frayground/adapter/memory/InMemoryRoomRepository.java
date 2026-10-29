package io.frayground.adapter.memory;
import io.frayground.application.port.*;
import io.frayground.domain.match.Match;
import io.frayground.domain.match.Room;
import java.util.*;
public final class InMemoryRoomRepository implements RoomStore {
  private final InMemoryStorage storage;
  public InMemoryRoomRepository(InMemoryStorage storage) { this.storage=storage; }
  public Optional<Room> find(UUID id) { return Optional.ofNullable(storage.readState().rooms.get(id)); }
  public void insert(Room room) { if (storage.writeState().rooms.putIfAbsent(room.roomId(), room)!=null) throw new StorageConflictException("Room already exists"); }
  public boolean replace(Room e,Room r) {
    if(!e.roomId().equals(r.roomId())) throw new IllegalArgumentException("room identity cannot change");
    MemoryState s=storage.writeState();
    if(r.activeMatchId()!=null) {
      Match match=s.matches.get(r.activeMatchId());
      if(match==null||!match.roomId().equals(r.roomId())) throw new StorageConflictException("Active match does not belong to room");
    }
    return s.rooms.replace(e.roomId(),e,r);
  }
  public LockedRoom lock(UUID id) { Room r=Optional.ofNullable(storage.writeState().rooms.get(id)).orElseThrow(); return new LockedRoom(r.roomId(),r.revision(),r.eventSeq(),r.activeMatchId(),r.executionMode(),r.executionStatus(),r.health(),r.healthError()); }
}
