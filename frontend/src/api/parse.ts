import type {
  AgentView,
  CommandResult,
  ExecutionMode,
  ExecutionStatus,
  Health,
  MatchResult,
  MatchStatus,
  ParsedRoom,
  ParticipantView,
  PromptDraft,
  PromptVersion,
  ResultStatus,
  RoomEvent,
  RoomEventType,
} from "./types";
import { EXECUTION_MODES, EXECUTION_STATUSES, HEALTH_VALUES, MATCH_STATUSES, RESULT_STATUSES, ROOM_EVENT_TYPES } from "./types";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const MAX_INT = 9007199254740991;

export class ResponseShapeError extends Error {
  constructor(path: string) {
    super(`响应缺少或无法识别字段 ${path}`);
    this.name = "ResponseShapeError";
  }
}

export function parseRoom(value: unknown): ParsedRoom {
  const body = record(value, "RoomView");
  const match = record(body.currentMatch, "currentMatch");
  if (!("game" in match)) throw new ResponseShapeError("currentMatch.game");
  record(match.turn, "currentMatch.turn");
  const result = parseResult(match.result);
  return {
    roomId: uuid(body.roomId, "roomId"),
    revision: int(body.revision, "revision", 0),
    eventCursor: int(body.eventCursor, "eventCursor", 0),
    executionMode: oneOf(body.executionMode, EXECUTION_MODES, "executionMode"),
    executionStatus: oneOf(body.executionStatus, EXECUTION_STATUSES, "executionStatus"),
    health: oneOf(body.health, HEALTH_VALUES, "health"),
    healthError: strOrNull(body.healthError, "healthError"),
    matchId: uuid(match.matchId, "currentMatch.matchId"),
    matchStatus: oneOf(match.status, MATCH_STATUSES, "currentMatch.status"),
    participants: parseParticipants(match.participants),
    result,
  };
}

export function parseCommandResult(value: unknown): CommandResult {
  const body = record(value, "CommandResult");
  return {
    commandId: uuid(body.commandId, "commandId"),
    roomRevision: int(body.roomRevision, "roomRevision", 0),
    matchId: uuid(body.matchId, "matchId"),
    executionMode: oneOf(body.executionMode, EXECUTION_MODES, "executionMode"),
    executionStatus: oneOf(body.executionStatus, EXECUTION_STATUSES, "executionStatus"),
  };
}

export function parseAgent(value: unknown): AgentView {
  const body = record(value, "AgentView");
  record(body.configuration, "configuration");
  return {
    agentId: uuid(body.agentId, "agentId"),
    name: str(body.name, "name"),
    draft: parseDraft(body.draft),
    publishedPrompt: parsePromptVersion(body.publishedPrompt),
  };
}

export function parseDraft(value: unknown): PromptDraft {
  const body = record(value, "PromptDraft");
  return {
    agentId: uuid(body.agentId, "draft.agentId"),
    revision: int(body.revision, "draft.revision", 1),
    content: str(body.content, "draft.content"),
    updatedAt: str(body.updatedAt, "draft.updatedAt"),
  };
}

export function parsePromptVersion(value: unknown): PromptVersion {
  const body = record(value, "PromptVersion");
  str(body.content, "publishedPrompt.content");
  return {
    promptVersionId: uuid(body.promptVersionId, "publishedPrompt.promptVersionId"),
    agentId: uuid(body.agentId, "publishedPrompt.agentId"),
    versionNo: int(body.versionNo, "publishedPrompt.versionNo", 1),
    sourceDraftRevision: int(body.sourceDraftRevision, "publishedPrompt.sourceDraftRevision", 1),
    createdAt: str(body.createdAt, "publishedPrompt.createdAt"),
  };
}

export function parseRoomEventData(data: string): RoomEvent | null {
  try {
    return parseRoomEvent(JSON.parse(data) as unknown);
  } catch {
    return null;
  }
}

function parseRoomEvent(value: unknown): RoomEvent {
  const body = record(value, "RoomEvent");
  return {
    sequence: int(body.sequence, "sequence", 1),
    roomId: uuid(body.roomId, "roomId"),
    matchId: body.matchId === null ? null : uuid(body.matchId, "matchId"),
    roomRevision: int(body.roomRevision, "roomRevision", 0),
    type: oneOf(body.type, ROOM_EVENT_TYPES, "type"),
    occurredAt: str(body.occurredAt, "occurredAt"),
    payload: record(body.payload, "payload"),
  };
}

function parseParticipants(value: unknown): ParticipantView[] {
  if (!Array.isArray(value)) throw new ResponseShapeError("currentMatch.participants");
  return value.map((item, index) => {
    const body = record(item, `participants[${index}]`);
    return {
      participantId: uuid(body.participantId, `participants[${index}].participantId`),
      agentId: uuid(body.agentId, `participants[${index}].agentId`),
      seatKey: str(body.seatKey, `participants[${index}].seatKey`),
    };
  });
}

function parseResult(value: unknown): MatchResult | null {
  if (value === null) return null;
  const body = record(value, "currentMatch.result");
  if (!Array.isArray(body.standings)) throw new ResponseShapeError("currentMatch.result.standings");
  if (!Array.isArray(body.winnerParticipantIds)) throw new ResponseShapeError("currentMatch.result.winnerParticipantIds");
  return {
    status: oneOf(body.status, RESULT_STATUSES, "currentMatch.result.status"),
    reason: strOrNull(body.reason, "currentMatch.result.reason"),
  };
}

function record(value: unknown, path: string): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) throw new ResponseShapeError(path);
  return value as Record<string, unknown>;
}

function str(value: unknown, path: string): string {
  if (typeof value !== "string") throw new ResponseShapeError(path);
  return value;
}

function strOrNull(value: unknown, path: string): string | null {
  if (value === null) return null;
  return str(value, path);
}

function uuid(value: unknown, path: string): string {
  const text = str(value, path);
  if (!UUID.test(text)) throw new ResponseShapeError(path);
  return text;
}

function int(value: unknown, path: string, min: number): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < min || value > MAX_INT) {
    throw new ResponseShapeError(path);
  }
  return value;
}

function oneOf<T extends string>(value: unknown, allowed: readonly T[], path: string): T {
  if (typeof value === "string" && (allowed as readonly string[]).includes(value)) return value as T;
  throw new ResponseShapeError(path);
}

export type { ExecutionMode, ExecutionStatus, Health, MatchStatus, ResultStatus, RoomEventType };
