import type { MatchResult, MatchStatus, ParsedRoom } from "../api/types";

export type SeatKey = "A" | "B";

export type SseState = "connecting" | "open" | "disconnected";

export type ControlKey = "start" | "pause" | "step" | "continue" | "reset";

export type SeatDraft = {
  agentId: string;
  name: string;
  content: string;
  savedContent: string;
  revision: number;
  publishedVersionNo: number;
  remote: { revision: number; content: string } | null;
  phase: "loading" | "ready" | "saving";
  error: string | null;
};

export type Seats = Record<SeatKey, SeatDraft | null>;

/** Parsed room plus the newest revision observed from a view, ack, or event. */
export type RoomSnapshot = ParsedRoom & {
  seenRevision: number;
};

export type { MatchResult, MatchStatus };
