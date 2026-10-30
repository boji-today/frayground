import { shouldApplyRevision } from "../../api/revision";
import { isExecutionMode, isExecutionStatus, type CommandResult, type ParsedRoom, type RoomEvent } from "../../api/types";
import type { RoomSnapshot } from "../types";

export function asRoomSnapshot(room: ParsedRoom, seenRevision = room.revision): RoomSnapshot {
  return { ...room, seenRevision };
}

export function mergeAuthoritativeRoom(known: RoomSnapshot | null, incoming: ParsedRoom): RoomSnapshot {
  if (known !== null && !shouldApplyRevision(known.seenRevision, incoming.revision)) return known;
  const seenRevision = known === null ? incoming.revision : Math.max(known.seenRevision, incoming.revision);
  return { ...incoming, seenRevision };
}

export function applyCommandAck(room: RoomSnapshot, ack: CommandResult): RoomSnapshot {
  if (!shouldApplyRevision(room.seenRevision, ack.roomRevision)) return room;
  return {
    ...room,
    revision: ack.roomRevision,
    seenRevision: Math.max(room.seenRevision, ack.roomRevision),
    matchId: ack.matchId,
    executionMode: ack.executionMode,
    executionStatus: ack.executionStatus,
  };
}

export function applyExecutionEvent(room: RoomSnapshot, event: RoomEvent): RoomSnapshot {
  if (event.type !== "EXECUTION_CHANGED") return room;
  if (!shouldApplyRevision(room.seenRevision, event.roomRevision)) return room;
  const next: RoomSnapshot = { ...room, seenRevision: event.roomRevision };
  if (isExecutionMode(event.payload.executionMode) && isExecutionStatus(event.payload.executionStatus)) {
    next.executionMode = event.payload.executionMode;
    next.executionStatus = event.payload.executionStatus;
  }
  return next;
}
