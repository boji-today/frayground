package io.frayground.adapter.memory;
import io.frayground.application.port.*;
import io.frayground.domain.match.*;
import java.util.*;
public final class InMemoryTurnRepository implements TurnStore {
  private final InMemoryStorage storage;
  public InMemoryTurnRepository(InMemoryStorage storage) { this.storage=storage; }
  public void insert(UUID matchId,TurnContext turn) {
    MemoryState s=storage.writeState();
    Match match=s.matches.get(matchId);
    if(match==null) throw new StorageConflictException("Owning match does not exist");
    if(turn.baseStateVersion()!=match.stateVersion()) throw new StorageConflictException("Turn base version does not match its match");
    for(UUID participantId:turn.requiredParticipants()) if(match.participants().stream().noneMatch(p->p.participantId().equals(participantId))) throw new StorageConflictException("Turn participant does not belong to its match");
    if(s.turns.putIfAbsent(turn.turnId(),turn)!=null) throw new StorageConflictException("Turn exists");
    s.turnMatches.put(turn.turnId(),matchId);
  }
  public Optional<TurnContext> find(UUID id) { return Optional.ofNullable(storage.readState().turns.get(id)); }
  public boolean replace(TurnContext e,TurnContext r) {
    if(!e.turnId().equals(r.turnId())) throw new IllegalArgumentException("turn identity cannot change");
    MemoryState s=storage.writeState();
    if(!s.turnMatches.containsKey(e.turnId())) throw new IllegalArgumentException("turn owner cannot change");
    if(!Objects.equals(s.turns.get(e.turnId()),e)) return false;
    if(e.baseStateVersion()!=r.baseStateVersion()||!e.requiredParticipants().equals(r.requiredParticipants())) throw new StorageConflictException("Turn base version and required participants cannot change");
    return s.turns.replace(e.turnId(),e,r);
  }
}
