export const SSE_REPEATED_FAILURE_LIMIT = 3;
export const SSE_AUTO_RESUBSCRIBE_LIMIT = 3;
export const SSE_STABLE_RESET_MS = 5000;

export type SseReadyState = 0 | 1 | 2;

export type ResubscribeDecision =
  | { action: "stay" }
  | { action: "mark_disconnected" }
  | { action: "resubscribe"; reason: "cursor_invalid" | "repeated_failure" | "health" | "closed" };

export function normalizeReadyState(value: number): SseReadyState {
  if (value === 0 || value === 1 || value === 2) return value;
  return 2;
}

/**
 * Browser EventSource reconnects with Last-Event-ID while CONNECTING.
 * A new cursor is required only for an invalid cursor, repeated failures, bad health, or a terminal close.
 */
export function decideResubscribe(input: {
  cursorInvalid: boolean;
  consecutiveFailures: number;
  healthNotOk: boolean;
  healthResubscribeConsumed: boolean;
  readyState: SseReadyState;
}): ResubscribeDecision {
  if (input.cursorInvalid) return { action: "resubscribe", reason: "cursor_invalid" };
  if (input.healthNotOk) {
    if (input.healthResubscribeConsumed) return { action: "mark_disconnected" };
    return { action: "resubscribe", reason: "health" };
  }
  if (input.consecutiveFailures >= SSE_REPEATED_FAILURE_LIMIT) {
    return { action: "resubscribe", reason: "repeated_failure" };
  }
  if (input.readyState === 2) return { action: "resubscribe", reason: "closed" };
  if (input.readyState === 0) return { action: "mark_disconnected" };
  return { action: "stay" };
}
