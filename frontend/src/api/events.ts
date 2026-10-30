import type { RoomEvent } from "./types";

const LISTED_TYPES = new Set<string>([
  "EXECUTION_CHANGED",
  "PROMPT_PUBLISHED",
  "DECISION_STARTED",
  "DECISION_FINISHED",
  "ACTION_RESOLVED",
  "MATCH_FINISHED",
  "MATCH_RESET",
  "ROOM_INITIALIZED",
]);

const PAYLOAD_KEYS: Record<string, readonly string[]> = {
  EXECUTION_CHANGED: ["executionMode", "executionStatus"],
  PROMPT_PUBLISHED: ["agentId", "promptVersionId", "versionNo"],
  DECISION_STARTED: ["decisionId", "participantId", "promptVersionId"],
  DECISION_FINISHED: ["decisionId", "status", "errorCode"],
  ACTION_RESOLVED: ["decisionId", "stateVersion"],
  MATCH_FINISHED: [],
  MATCH_RESET: ["currentMatchId"],
  ROOM_INITIALIZED: ["currentMatchId"],
};

export type ListedEvent = {
  sequence: number;
  type: string;
  occurredAt: string;
  payload: Record<string, string | number | boolean>;
};

export function reduceEventLog(
  state: { events: readonly ListedEvent[]; maxSeen: number },
  event: RoomEvent,
): { events: ListedEvent[]; maxSeen: number; dropped: boolean } {
  if (event.sequence <= state.maxSeen) {
    return { events: [...state.events], maxSeen: state.maxSeen, dropped: true };
  }
  if (!LISTED_TYPES.has(event.type)) {
    return { events: [...state.events], maxSeen: event.sequence, dropped: false };
  }
  return {
    events: [...state.events, {
      sequence: event.sequence,
      type: event.type,
      occurredAt: event.occurredAt,
      payload: pickPayload(event.type, event.payload),
    }],
    maxSeen: event.sequence,
    dropped: false,
  };
}

export function formatPayload(payload: Record<string, string | number | boolean>): string {
  return Object.entries(payload).map(([key, value]) => `${key}=${String(value)}`).join(" ");
}

function pickPayload(type: string, payload: Record<string, unknown>): Record<string, string | number | boolean> {
  const picked: Record<string, string | number | boolean> = {};
  for (const key of PAYLOAD_KEYS[type] ?? []) {
    const value = payload[key];
    if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") picked[key] = value;
  }
  return picked;
}
