export const EXECUTION_MODES = ["AUTO", "STEP"] as const;
export type ExecutionMode = (typeof EXECUTION_MODES)[number];

export const EXECUTION_STATUSES = ["PAUSED", "RUNNING", "PAUSING"] as const;
export type ExecutionStatus = (typeof EXECUTION_STATUSES)[number];

export const HEALTH_VALUES = ["OK", "ERROR"] as const;
export type Health = (typeof HEALTH_VALUES)[number];

export const MATCH_STATUSES = ["READY", "ACTIVE", "FINISHED", "ABORTED"] as const;
export type MatchStatus = (typeof MATCH_STATUSES)[number];

export const RESULT_STATUSES = ["ONGOING", "WIN", "DRAW"] as const;
export type ResultStatus = (typeof RESULT_STATUSES)[number];

export const CONTROL_TYPES = ["start", "pause", "continue", "step", "reset"] as const;
export type ControlCommandType = (typeof CONTROL_TYPES)[number];

export const ROOM_EVENT_TYPES = [
  "ROOM_INITIALIZED",
  "MATCH_RESET",
  "EXECUTION_CHANGED",
  "PROMPT_PUBLISHED",
  "CONFIGURATION_PUBLISHED",
  "DECISION_STARTED",
  "DECISION_FINISHED",
  "ACTION_RESOLVED",
  "MATCH_FINISHED",
  "SERVER_RECOVERED",
  "ROOM_UNAVAILABLE",
] as const;
export type RoomEventType = (typeof ROOM_EVENT_TYPES)[number];

export type ParticipantView = {
  participantId: string;
  agentId: string;
  seatKey: string;
};

export type MatchResult = {
  status: ResultStatus;
  reason: string | null;
};

/** RoomView fields this client keeps. `game` is validated present and discarded. */
export type ParsedRoom = {
  roomId: string;
  revision: number;
  eventCursor: number;
  executionMode: ExecutionMode;
  executionStatus: ExecutionStatus;
  health: Health;
  healthError: string | null;
  matchId: string;
  matchStatus: MatchStatus;
  participants: ParticipantView[];
  result: MatchResult | null;
};

export type CommandResult = {
  commandId: string;
  roomRevision: number;
  matchId: string;
  executionMode: ExecutionMode;
  executionStatus: ExecutionStatus;
};

export type PromptDraft = {
  agentId: string;
  revision: number;
  content: string;
  updatedAt: string;
};

export type PromptVersion = {
  promptVersionId: string;
  agentId: string;
  versionNo: number;
  sourceDraftRevision: number;
  createdAt: string;
};

export type AgentView = {
  agentId: string;
  name: string;
  draft: PromptDraft;
  publishedPrompt: PromptVersion;
};

export type DraftUpdate = {
  expectedDraftRevision: number;
  content: string;
};

export type ControlCommand = {
  commandId: string;
  expectedRoomRevision: number;
  expectedMatchId: string;
  type: ControlCommandType;
};

export type PublishPrompt = {
  commandId: string;
  expectedRoomRevision: number;
  expectedMatchId: string;
  expectedDraftRevision: number;
};

export type RoomEvent = {
  sequence: number;
  roomId: string;
  matchId: string | null;
  roomRevision: number;
  type: RoomEventType;
  occurredAt: string;
  payload: Record<string, unknown>;
};

export function isExecutionMode(value: unknown): value is ExecutionMode {
  return typeof value === "string" && includes(EXECUTION_MODES, value);
}

export function isExecutionStatus(value: unknown): value is ExecutionStatus {
  return typeof value === "string" && includes(EXECUTION_STATUSES, value);
}

function includes<T extends string>(values: readonly T[], value: string): value is T {
  return (values as readonly string[]).includes(value);
}
