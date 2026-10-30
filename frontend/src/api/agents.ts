import { requestJson } from "./http";
import { parseAgent, parseDraft, parsePromptVersion } from "./parse";
import type { AgentView, DraftUpdate, PromptDraft, PromptVersion, PublishPrompt } from "./types";

export function getAgent(agentId: string, signal?: AbortSignal): Promise<AgentView> {
  return requestJson(`/agents/${encodeURIComponent(agentId)}/configuration`, {
    method: "GET",
    signal,
  }).then(parseAgent);
}

export function savePromptDraft(agentId: string, body: DraftUpdate, signal?: AbortSignal): Promise<PromptDraft> {
  return requestJson(`/agents/${encodeURIComponent(agentId)}/prompt-draft`, {
    method: "PUT",
    body,
    signal,
  }).then(parseDraft);
}

export function publishPrompt(roomId: string, agentId: string, body: PublishPrompt, signal?: AbortSignal): Promise<PromptVersion> {
  return requestJson(`/rooms/${encodeURIComponent(roomId)}/agents/${encodeURIComponent(agentId)}/prompt-versions`, {
    method: "POST",
    body,
    signal,
  }).then(parsePromptVersion);
}
