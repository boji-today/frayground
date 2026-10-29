package io.frayground.adapter.memory;

import io.frayground.application.port.StorageConflictException;
import io.frayground.domain.action.Decision;
import io.frayground.domain.action.DecisionStatus;
import io.frayground.domain.game.GameEvent;
import io.frayground.domain.game.GameKey;
import io.frayground.domain.game.Participant;
import io.frayground.domain.game.SchedulingMode;
import io.frayground.domain.match.ExecutionMode;
import io.frayground.domain.match.ExecutionStatus;
import io.frayground.domain.match.Match;
import io.frayground.domain.match.MatchStatus;
import io.frayground.domain.match.Room;
import io.frayground.domain.match.RoomEvent;
import io.frayground.domain.match.RoomHealth;
import io.frayground.domain.match.TurnContext;
import io.frayground.domain.match.TurnStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryAggregateOwnershipTest {
  @Test void roomReplaceRejectsForeignActiveMatch() {
    World world=world();
    Room foreign=room(world.roomA,world.matchB);
    assertThrows(StorageConflictException.class,()->world.tx.executeWithoutResult(s->world.rooms.replace(world.roomAStored,foreign)));
    assertEquals(world.roomAStored,world.rooms.find(world.roomA).orElseThrow());
    Room owned=room(world.roomA,world.matchA);
    boolean replaced=world.tx.execute(s->world.rooms.replace(world.roomAStored,owned));
    assertTrue(replaced);
    assertEquals(owned,world.rooms.find(world.roomA).orElseThrow());
    assertEquals(world.roomBStored,world.rooms.find(world.roomB).orElseThrow());
  }

  @Test void matchReplaceRejectsForeignCurrentTurn() {
    World world=world();
    Match foreign=match(world.matchA,world.roomA,world.participantA,world.turnB);
    assertThrows(StorageConflictException.class,()->world.tx.executeWithoutResult(s->world.matches.replace(world.matchAStored,foreign)));
    assertEquals(world.matchAStored,world.matches.find(world.matchA).orElseThrow());
    Match owned=match(world.matchA,world.roomA,world.participantA,world.turnA);
    boolean replaced=world.tx.execute(s->world.matches.replace(world.matchAStored,owned));
    assertTrue(replaced);
    assertEquals(owned,world.matches.find(world.matchA).orElseThrow());
    assertEquals(world.matchBStored,world.matches.find(world.matchB).orElseThrow());
  }

  @Test void turnInsertRejectsForeignParticipantAndStaleVersion() {
    World world=world();
    UUID foreignId=UUID.randomUUID();
    TurnContext foreign=turn(foreignId,world.participantB,0);
    assertThrows(StorageConflictException.class,()->world.tx.executeWithoutResult(s->world.turns.insert(world.matchA,foreign)));
    assertTrue(world.turns.find(foreignId).isEmpty());
    UUID staleId=UUID.randomUUID();
    TurnContext stale=turn(staleId,world.participantA,1);
    assertThrows(StorageConflictException.class,()->world.tx.executeWithoutResult(s->world.turns.insert(world.matchA,stale)));
    assertTrue(world.turns.find(staleId).isEmpty());
    assertEquals(world.turnAStored,world.turns.find(world.turnA).orElseThrow());
    TurnContext resolved=new TurnContext(world.turnA,1,1,0,SchedulingMode.TURN_BASED_SEQUENTIAL,List.of(world.participantA),TurnStatus.RESOLVED);
    boolean replaced=world.tx.execute(s->world.turns.replace(world.turnAStored,resolved));
    assertTrue(replaced);
    assertEquals(resolved,world.turns.find(world.turnA).orElseThrow());
    TurnContext unowned=turn(UUID.randomUUID(),world.participantA,0);
    assertThrows(IllegalArgumentException.class,()->world.tx.executeWithoutResult(s->world.turns.replace(unowned,unowned)));
    assertTrue(world.turns.find(unowned.turnId()).isEmpty());
  }

  @Test void gameEventAppendRejectsForeignDecision() {
    World world=world();
    GameEvent kept=gameEvent(world.matchA,1,null);
    world.tx.executeWithoutResult(s->world.gameEvents.append(kept));
    GameEvent foreign=gameEvent(world.matchA,2,world.decisionB);
    assertThrows(StorageConflictException.class,()->world.tx.executeWithoutResult(s->world.gameEvents.append(foreign)));
    assertEquals(List.of(kept),world.gameEvents.readAfter(world.matchA,0,10));
    GameEvent owned=gameEvent(world.matchA,2,world.decisionA);
    world.tx.executeWithoutResult(s->world.gameEvents.append(owned));
    assertEquals(List.of(kept,owned),world.gameEvents.readAfter(world.matchA,0,10));
    assertTrue(world.gameEvents.readAfter(world.matchB,0,10).isEmpty());
  }

  @Test void roomEventAppendRejectsForeignMatch() {
    World world=world();
    RoomEvent kept=roomEvent(world.roomA,1,null);
    world.tx.executeWithoutResult(s->world.roomEvents.append(kept));
    RoomEvent foreign=roomEvent(world.roomA,2,world.matchB);
    assertThrows(StorageConflictException.class,()->world.tx.executeWithoutResult(s->world.roomEvents.append(foreign)));
    assertEquals(List.of(kept),world.roomEvents.readAfter(world.roomA,0,10));
    RoomEvent owned=roomEvent(world.roomA,2,world.matchA);
    world.tx.executeWithoutResult(s->world.roomEvents.append(owned));
    assertEquals(List.of(kept,owned),world.roomEvents.readAfter(world.roomA,0,10));
    assertTrue(world.roomEvents.readAfter(world.roomB,0,10).isEmpty());
  }

  private static World world() {
    World world=new World();
    world.tx.executeWithoutResult(s->{
      world.rooms.insert(world.roomAStored); world.rooms.insert(world.roomBStored);
      world.matches.insert(world.matchAStored); world.matches.insert(world.matchBStored);
      world.turns.insert(world.matchA,world.turnAStored); world.turns.insert(world.matchB,world.turnBStored);
      world.decisions.insert(world.decisionAStored); world.decisions.insert(world.decisionBStored);
    });
    return world;
  }

  private static Room room(UUID roomId,UUID activeMatchId) {
    return new Room(roomId,0,0,activeMatchId,ExecutionMode.STEP,ExecutionStatus.PAUSED,RoomHealth.OK,null);
  }

  private static Match match(UUID matchId,UUID roomId,UUID participantId,UUID currentTurnId) {
    return new Match(matchId,roomId,new GameKey("ownership","1"),MatchStatus.READY,0,0,currentTurnId,List.of(new Participant(participantId,UUID.randomUUID(),"seat")),null);
  }

  private static TurnContext turn(UUID turnId,UUID participantId,long baseStateVersion) {
    return new TurnContext(turnId,1,1,baseStateVersion,SchedulingMode.TURN_BASED_SEQUENTIAL,List.of(participantId),TurnStatus.OPEN);
  }

  private static Decision decision(UUID decisionId,UUID matchId,UUID turnId,UUID participantId) {
    return new Decision(decisionId,matchId,turnId,participantId,DecisionStatus.PENDING,0,0,null,null,null,null,null,null,null);
  }

  private static GameEvent gameEvent(UUID matchId,long sequence,UUID decisionId) {
    return new GameEvent(matchId,sequence,0,decisionId,"fact",1,Map.of(),Instant.EPOCH);
  }

  private static RoomEvent roomEvent(UUID roomId,long sequence,UUID matchId) {
    return new RoomEvent(roomId,sequence,matchId,0,"fact",Map.of(),Instant.EPOCH);
  }

  private static final class World {
    final InMemoryStorage storage=new InMemoryStorage();
    final TransactionTemplate tx=new TransactionTemplate(new InMemoryTransactionManager(storage));
    final InMemoryRoomRepository rooms=new InMemoryRoomRepository(storage);
    final InMemoryMatchRepository matches=new InMemoryMatchRepository(storage);
    final InMemoryTurnRepository turns=new InMemoryTurnRepository(storage);
    final InMemoryDecisionRepository decisions=new InMemoryDecisionRepository(storage);
    final InMemoryGameEventRepository gameEvents=new InMemoryGameEventRepository(storage);
    final InMemoryRoomEventRepository roomEvents=new InMemoryRoomEventRepository(storage);
    final UUID roomA=UUID.randomUUID(), roomB=UUID.randomUUID();
    final UUID matchA=UUID.randomUUID(), matchB=UUID.randomUUID();
    final UUID turnA=UUID.randomUUID(), turnB=UUID.randomUUID();
    final UUID participantA=UUID.randomUUID(), participantB=UUID.randomUUID();
    final UUID decisionA=UUID.randomUUID(), decisionB=UUID.randomUUID();
    final Room roomAStored=room(roomA,null), roomBStored=room(roomB,null);
    final Match matchAStored=match(matchA,roomA,participantA,null), matchBStored=match(matchB,roomB,participantB,null);
    final TurnContext turnAStored=turn(turnA,participantA,0), turnBStored=turn(turnB,participantB,0);
    final Decision decisionAStored=decision(decisionA,matchA,turnA,participantA), decisionBStored=decision(decisionB,matchB,turnB,participantB);
  }
}
