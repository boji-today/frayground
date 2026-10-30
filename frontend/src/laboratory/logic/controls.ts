import type { ExecutionStatus, Health, MatchStatus } from "../../api/types";
import type { ControlKey, SseState } from "../types";

export type ControlAvailability = Record<ControlKey, boolean>;

export function controlAvailability(input: {
  matchStatus: MatchStatus | null;
  executionStatus: ExecutionStatus | null;
  health: Health | null;
  sse: SseState;
  commandPending: boolean;
  commandUncertain: boolean;
}): ControlAvailability {
  const known = input.matchStatus !== null && input.executionStatus !== null && input.health !== null;
  const terminal = input.matchStatus === "FINISHED" || input.matchStatus === "ABORTED";
  const base = known && !input.commandPending;
  const advance = base && input.sse === "open" && !terminal && input.health === "OK" && !input.commandUncertain;
  const paused = input.executionStatus === "PAUSED";
  return {
    start: advance && input.matchStatus === "READY" && paused,
    step: advance && (input.matchStatus === "READY" || input.matchStatus === "ACTIVE") && paused,
    continue: advance && input.matchStatus === "ACTIVE" && paused,
    pause: base && !terminal && input.matchStatus === "ACTIVE"
      && (input.executionStatus === "RUNNING" || input.executionStatus === "PAUSING"),
    reset: base,
  };
}

/** Disabled advance controls can still be clicked once; that click sends a new commandId and then clears the flag. */
export function uncertainClickSends(
  type: ControlKey,
  commandUncertain: boolean,
  allowedIfResolved: ControlAvailability,
): boolean {
  if (!commandUncertain) return false;
  return (type === "start" || type === "step" || type === "continue") && allowedIfResolved[type];
}

export function resetResultKnown(beforeMatchId: string | null, refreshedMatchId: string | null): boolean {
  return beforeMatchId !== null && refreshedMatchId !== null && refreshedMatchId !== beforeMatchId;
}

export function uncertainAcknowledge(
  commandUncertain: boolean,
  allowedIfResolved: ControlAvailability,
): ControlAvailability {
  return {
    start: uncertainClickSends("start", commandUncertain, allowedIfResolved),
    pause: false,
    step: uncertainClickSends("step", commandUncertain, allowedIfResolved),
    continue: uncertainClickSends("continue", commandUncertain, allowedIfResolved),
    reset: false,
  };
}
