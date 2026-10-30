import { isTransportLoss } from "./http";
import type { ControlCommand, ControlCommandType, DraftUpdate, PublishPrompt } from "./types";

export function newCommandId(): string {
  return crypto.randomUUID();
}

export function buildDraftUpdate(input: { expectedDraftRevision: number; content: string }): DraftUpdate {
  return {
    expectedDraftRevision: input.expectedDraftRevision,
    content: input.content,
  };
}

export function buildControlCommand(input: {
  commandId: string;
  expectedRoomRevision: number;
  expectedMatchId: string;
  type: ControlCommandType;
}): ControlCommand {
  return {
    commandId: input.commandId,
    expectedRoomRevision: input.expectedRoomRevision,
    expectedMatchId: input.expectedMatchId,
    type: input.type,
  };
}

export function buildPublishPrompt(input: {
  commandId: string;
  expectedRoomRevision: number;
  expectedMatchId: string;
  expectedDraftRevision: number;
}): PublishPrompt {
  return {
    commandId: input.commandId,
    expectedRoomRevision: input.expectedRoomRevision,
    expectedMatchId: input.expectedMatchId,
    expectedDraftRevision: input.expectedDraftRevision,
  };
}

export function decideTransportRetry(input: {
  transportLost: boolean;
  alreadyRetried: boolean;
}): "retry_same_body" | "surface_failure" {
  if (input.transportLost && !input.alreadyRetried) return "retry_same_body";
  return "surface_failure";
}

/** Retries the same closure once. Callers must close over one commandId and body. */
export async function postIdempotent<T>(send: () => Promise<T>): Promise<T> {
  let alreadyRetried = false;
  for (;;) {
    try {
      return await send();
    } catch (error) {
      const decision = decideTransportRetry({
        transportLost: isTransportLoss(error),
        alreadyRetried,
      });
      if (decision !== "retry_same_body") throw error;
      alreadyRetried = true;
    }
  }
}
