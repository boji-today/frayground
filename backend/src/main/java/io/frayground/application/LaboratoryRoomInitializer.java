package io.frayground.application;

import io.frayground.application.port.*;
import io.frayground.domain.game.GameKey;
import io.frayground.domain.game.Participant;
import io.frayground.domain.match.*;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.annotation.Transactional;

/** Creates the single laboratory room and its initial match atomically. */
public class LaboratoryRoomInitializer {
  private final RoomStore rooms;
  private final MatchStore matches;
  private final TurnStore turns;
  private final SnapshotStore snapshots;
  private final RoomEventRepository events;
  private final MatchFactory factory;
  private final Clock clock;
  private final Supplier<UUID> ids;
  private final UUID roomId, agentA, agentB;

  public LaboratoryRoomInitializer(RoomStore rooms, MatchStore matches, TurnStore turns,
      SnapshotStore snapshots, RoomEventRepository events, MatchFactory factory, Clock clock,
      Supplier<UUID> ids, UUID roomId, UUID agentA, UUID agentB) {
    this.rooms = rooms; this.matches = matches; this.turns = turns; this.snapshots = snapshots;
    this.events = events; this.factory = factory; this.clock = clock; this.ids = ids;
    this.roomId = roomId; this.agentA = agentA; this.agentB = agentB;
  }

  public record Result(boolean created, Room room) {}

  @Transactional(transactionManager = "gameTransactionManager")
  public Result initialize() {
    var existing = rooms.find(roomId);
    if (existing.isPresent()) return new Result(false, existing.get());
    Room empty = new Room(roomId, 0, 0, null, ExecutionMode.STEP,
        ExecutionStatus.PAUSED, RoomHealth.OK, null);
    rooms.insert(empty);
    var participants = List.of(new Participant(ids.get(), agentA, "A"),
        new Participant(ids.get(), agentB, "B"));
    var created = factory.create(roomId, new GameKey("connect-four", "1"), participants);
    matches.insert(created.match());
    if (created.firstTurn() != null) turns.insert(created.match().matchId(), created.firstTurn());
    snapshots.append(created.initialSnapshot());
    Room ready = new Room(roomId, 0, 1, created.match().matchId(), ExecutionMode.STEP,
        ExecutionStatus.PAUSED, RoomHealth.OK, null);
    if (!rooms.replace(empty, ready)) throw new IllegalStateException("room initialization conflict");
    events.append(new RoomEvent(roomId, 1, ready.activeMatchId(), ready.revision(),
        "ROOM_INITIALIZED", Map.of(), clock.instant()));
    return new Result(true, ready);
  }
}
