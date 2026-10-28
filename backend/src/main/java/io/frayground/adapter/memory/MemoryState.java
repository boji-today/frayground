package io.frayground.adapter.memory;

import io.frayground.domain.action.*;
import io.frayground.domain.game.*;
import io.frayground.domain.match.*;
import java.util.*;

/** Package-private complete image of the in-memory database. */
final class MemoryState {
  final Map<UUID, Room> rooms = new LinkedHashMap<>();
  final Map<UUID, Match> matches = new LinkedHashMap<>();
  final Map<UUID, TurnContext> turns = new LinkedHashMap<>();
  final Map<UUID, UUID> turnMatches = new HashMap<>();
  final Map<UUID, Decision> decisions = new LinkedHashMap<>();
  final Map<UUID, DecisionRecord> records = new LinkedHashMap<>();
  final Map<String, StateSnapshot> snapshots = new LinkedHashMap<>();
  final Map<String, GameEvent> gameEvents = new LinkedHashMap<>();
  final Map<String, RoomEvent> roomEvents = new LinkedHashMap<>();
  final Map<UUID, Settlement> settlements = new LinkedHashMap<>();
  final Map<String, CommandReceipt> commandReceipts = new LinkedHashMap<>();
  final Map<UUID, ActionReceipt> actionReceipts = new LinkedHashMap<>();
  MemoryState copy() {
    MemoryState c = new MemoryState();
    c.rooms.putAll(rooms); c.matches.putAll(matches); c.turns.putAll(turns); c.turnMatches.putAll(turnMatches);
    c.decisions.putAll(decisions); c.records.putAll(records); c.snapshots.putAll(snapshots); c.gameEvents.putAll(gameEvents);
    c.roomEvents.putAll(roomEvents); c.settlements.putAll(settlements); c.commandReceipts.putAll(commandReceipts); c.actionReceipts.putAll(actionReceipts);
    return c;
  }
  record CommandReceipt(byte[] hash, CommandResult result) {}
  record ActionReceipt(ActionEnvelope envelope, ActionSubmissionResult result) {}
}
