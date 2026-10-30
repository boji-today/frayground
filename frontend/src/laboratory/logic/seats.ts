import type { AgentView, ParticipantView } from "../../api/types";
import { isDraftDirty } from "../../api/draft";
import type { SeatDraft, SeatKey } from "../types";

export function findSeatAgentId(participants: readonly ParticipantView[], seatKey: SeatKey): string | null {
  const found = participants.find((item) => item.seatKey === seatKey);
  return found ? found.agentId : null;
}

export function loadingSeat(agentId: string): SeatDraft {
  return {
    agentId,
    name: "",
    content: "",
    savedContent: "",
    revision: 0,
    publishedVersionNo: 0,
    remote: null,
    phase: "loading",
    error: null,
  };
}

export function seatFromAgent(view: AgentView): SeatDraft {
  return {
    agentId: view.agentId,
    name: view.name,
    content: view.draft.content,
    savedContent: view.draft.content,
    revision: view.draft.revision,
    publishedVersionNo: view.publishedPrompt.versionNo,
    remote: null,
    phase: "ready",
    error: null,
  };
}

export function isDraftClean(seat: SeatDraft): boolean {
  return seat.phase === "ready" && seat.remote === null && !isDraftDirty({
    loaded: true,
    content: seat.content,
    savedContent: seat.savedContent,
  });
}

export function applyAgentIfClean(current: SeatDraft, view: AgentView): SeatDraft {
  if (!isDraftClean(current)) {
    return {
      ...current,
      name: view.name,
      publishedVersionNo: view.publishedPrompt.versionNo,
    };
  }
  return seatFromAgent(view);
}

export function canSaveDraft(seat: SeatDraft | null): boolean {
  if (!seat || seat.phase !== "ready" || seat.revision < 1) return false;
  return isDraftDirty({ loaded: true, content: seat.content, savedContent: seat.savedContent })
    || seat.remote !== null
    || seat.error !== null;
}

export function canPublishDraft(seat: SeatDraft | null): boolean {
  return seat !== null && seat.phase === "ready" && seat.revision >= 1;
}

export function draftStatusLabel(seat: SeatDraft): "未保存" | "已保存" | "保存中" {
  if (seat.phase === "saving") return "保存中";
  if (seat.remote !== null || seat.content !== seat.savedContent) return "未保存";
  return "已保存";
}
