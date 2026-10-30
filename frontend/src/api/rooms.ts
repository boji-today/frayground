import { requestJson } from "./http";
import { parseCommandResult, parseRoom } from "./parse";
import type { CommandResult, ControlCommand, ParsedRoom } from "./types";

export function ensureCurrentRoom(signal?: AbortSignal): Promise<ParsedRoom> {
  return requestJson("/rooms/current", { method: "POST", signal }).then(parseRoom);
}

export function getCurrentRoom(signal?: AbortSignal): Promise<ParsedRoom> {
  return requestJson("/rooms/current", { method: "GET", signal }).then(parseRoom);
}

export function postCommand(roomId: string, body: ControlCommand, signal?: AbortSignal): Promise<CommandResult> {
  return requestJson(`/rooms/${encodeURIComponent(roomId)}/commands`, {
    method: "POST",
    body,
    signal,
  }).then(parseCommandResult);
}
