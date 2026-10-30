export function isDraftDirty(input: { loaded: boolean; content: string; savedContent: string }): boolean {
  return input.loaded && input.content !== input.savedContent;
}

/** Keeps whatever the user has typed since the request snapshot. */
export function applyDraftSave(input: {
  currentContent: string;
  responseContent: string;
  responseRevision: number;
}): { content: string; savedContent: string; revision: number } {
  return {
    content: input.currentContent,
    savedContent: input.responseContent,
    revision: input.responseRevision,
  };
}

export type DraftLossReconcile =
  | { kind: "saved"; revision: number; savedContent: string }
  | { kind: "not_saved" };

export function reconcileDraftAfterLoss(input: {
  sentContent: string;
  serverContent: string;
  serverRevision: number;
}): DraftLossReconcile {
  if (input.serverContent === input.sentContent) {
    return { kind: "saved", revision: input.serverRevision, savedContent: input.serverContent };
  }
  return { kind: "not_saved" };
}
