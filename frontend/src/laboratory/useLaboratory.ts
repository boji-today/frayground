import { useEffect, useRef, useState } from "react";
import { getAgent, publishPrompt, savePromptDraft } from "../api/agents";
import {
  buildControlCommand,
  buildDraftUpdate,
  buildPublishPrompt,
  newCommandId,
  postIdempotent,
} from "../api/commands";
import { SSE_AUTO_RESUBSCRIBE_LIMIT, SSE_STABLE_RESET_MS, decideResubscribe, normalizeReadyState } from "../api/cursor";
import { applyDraftSave, reconcileDraftAfterLoss } from "../api/draft";
import { reduceEventLog, type ListedEvent } from "../api/events";
import { errorMessage, isAbortError, isApiError, isTransportLoss } from "../api/http";
import { getCurrentRoom, ensureCurrentRoom, postCommand } from "../api/rooms";
import { subscribeRoomEvents } from "../api/sse";
import type { ParsedRoom, RoomEvent } from "../api/types";
import { controlAvailability, resetResultKnown, uncertainAcknowledge, uncertainClickSends } from "./logic/controls";
import { applyCommandAck, applyExecutionEvent, asRoomSnapshot, mergeAuthoritativeRoom } from "./logic/roomState";
import { applyAgentIfClean, findSeatAgentId, isDraftClean, loadingSeat, seatFromAgent } from "./logic/seats";
import type { ControlKey, RoomSnapshot, SeatKey, Seats, SseState } from "./types";

type Actions = {
  boot: (signal: AbortSignal, cancelled: () => boolean) => Promise<void>;
  stopSse: () => void;
};

export function useLaboratory() {
  const [room, setRoom] = useState<RoomSnapshot | null>(null);
  const [seats, setSeats] = useState<Seats>({ A: null, B: null });
  const [events, setEvents] = useState<ListedEvent[]>([]);
  const [sse, setSse] = useState<SseState>("connecting");
  const sseRef = useRef<SseState>("connecting");
  const [sseGaveUp, setSseGaveUp] = useState(false);
  const [pageError, setPageError] = useState<string | null>(null);
  const [commandError, setCommandError] = useState<string | null>(null);
  const [commandPending, setCommandPending] = useState(false);
  const [commandUncertain, setCommandUncertain] = useState(false);

  const roomRef = useRef<RoomSnapshot | null>(null);
  const seatsRef = useRef<Seats>({ A: null, B: null });
  const eventsRef = useRef<ListedEvent[]>([]);
  const maxSeenRef = useRef(0);
  const sseGen = useRef(0);
  const closeSse = useRef<(() => void) | null>(null);
  const failures = useRef(0);
  const autoResubscribes = useRef(0);
  const healthConsumed = useRef(false);
  const stableTimer = useRef<number | null>(null);
  const commandLock = useRef(false);
  const seatLock = useRef<Record<SeatKey, boolean>>({ A: false, B: false });
  const resubscribeJob = useRef<Promise<ParsedRoom | null> | null>(null);
  const pendingClear = useRef(false);
  const mounted = useRef(true);
  const actions = useRef<Actions | null>(null);

  function commitRoom(next: RoomSnapshot | null) {
    roomRef.current = next;
    setRoom(next);
  }

  function commitSse(next: SseState) {
    sseRef.current = next;
    setSse(next);
  }

  function commitSeats(update: (prev: Seats) => Seats) {
    const next = update(seatsRef.current);
    seatsRef.current = next;
    setSeats(next);
  }

  function commitEvents(next: ListedEvent[], maxSeen: number) {
    eventsRef.current = next;
    maxSeenRef.current = maxSeen;
    setEvents(next);
  }

  function clearStableTimer() {
    if (stableTimer.current !== null) {
      window.clearTimeout(stableTimer.current);
      stableTimer.current = null;
    }
  }

  function stopSse() {
    clearStableTimer();
    sseGen.current += 1;
    failures.current = 0;
    const close = closeSse.current;
    closeSse.current = null;
    close?.();
  }

  function openSse(roomId: string, after: number) {
    stopSse();
    const generation = sseGen.current;
    commitSse("connecting");
    closeSse.current = subscribeRoomEvents(roomId, after, {
      onOpen: () => {
        if (generation !== sseGen.current || !mounted.current) return;
        failures.current = 0;
        commitSse("open");
        setSseGaveUp(false);
        clearStableTimer();
        stableTimer.current = window.setTimeout(() => {
          if (generation === sseGen.current) autoResubscribes.current = 0;
        }, SSE_STABLE_RESET_MS);
      },
      onEvent: (event) => {
        void onEvent(event, generation);
      },
      onError: (info) => onSseError(info.readyState, generation),
    });
  }

  function onSseError(readyState: number, generation: number) {
    if (generation !== sseGen.current || !mounted.current) return;
    failures.current += 1;
    const decision = decideResubscribe({
      cursorInvalid: false,
      consecutiveFailures: failures.current,
      healthNotOk: false,
      healthResubscribeConsumed: false,
      readyState: normalizeReadyState(readyState),
    });
    if (decision.action === "stay") return;
    commitSse("disconnected");
    if (decision.action === "mark_disconnected") return;
    if (autoResubscribes.current >= SSE_AUTO_RESUBSCRIBE_LIMIT) {
      stopSse();
      setSseGaveUp(true);
      return;
    }
    autoResubscribes.current += 1;
    void resubscribe(false);
  }

  async function refreshRoom(): Promise<ParsedRoom | null> {
    const fetched = await getCurrentRoom();
    if (!mounted.current) return null;
    const merged = mergeAuthoritativeRoom(roomRef.current, fetched);
    commitRoom(merged);
    if (merged.health === "OK") {
      healthConsumed.current = false;
      return fetched;
    }
    const decision = decideResubscribe({
      cursorInvalid: false,
      consecutiveFailures: 0,
      healthNotOk: true,
      healthResubscribeConsumed: healthConsumed.current,
      readyState: 1,
    });
    if (decision.action === "resubscribe") {
      healthConsumed.current = true;
      await resubscribe(false);
    }
    return fetched;
  }

  function resubscribe(clearList: boolean): Promise<ParsedRoom | null> {
    if (clearList) pendingClear.current = true;
    if (resubscribeJob.current) return resubscribeJob.current;
    const job = runResubscribe().finally(() => {
      resubscribeJob.current = null;
    });
    resubscribeJob.current = job;
    return job;
  }

  async function runResubscribe(): Promise<ParsedRoom | null> {
    while (mounted.current) {
      const clear = pendingClear.current;
      pendingClear.current = false;
      stopSse();
      commitSse("connecting");
      if (clear) commitEvents([], 0);
      let fetched: ParsedRoom;
      try {
        fetched = await getCurrentRoom();
      } catch (error) {
        if (!mounted.current || isAbortError(error)) return null;
        setCommandError(errorMessage(error));
        commitSse("disconnected");
        setSseGaveUp(true);
        return null;
      }
      if (!mounted.current) return null;
      if (pendingClear.current) continue;
      const merged = mergeAuthoritativeRoom(roomRef.current, fetched);
      commitRoom(merged);
      const maxSeen = clear ? merged.eventCursor : Math.max(maxSeenRef.current, merged.eventCursor);
      maxSeenRef.current = maxSeen;
      healthConsumed.current = merged.health !== "OK";
      openSse(merged.roomId, merged.eventCursor);
      if (!pendingClear.current) return fetched;
    }
    return null;
  }

  async function onEvent(event: RoomEvent, generation: number) {
    if (generation !== sseGen.current || !mounted.current) return;
    const current = roomRef.current;
    if (current && event.roomId !== current.roomId) return;
    const reduced = reduceEventLog({ events: eventsRef.current, maxSeen: maxSeenRef.current }, event);
    if (reduced.dropped) return;
    const seen = roomRef.current;
    if (seen && event.roomRevision > seen.seenRevision) {
      commitRoom({ ...seen, seenRevision: event.roomRevision });
    }
    if (event.type === "MATCH_RESET") {
      await resubscribe(true);
      return;
    }
    commitEvents(reduced.events, reduced.maxSeen);
    if (generation !== sseGen.current) return;
    if (event.type === "EXECUTION_CHANGED") {
      const latest = roomRef.current;
      if (latest) commitRoom(applyExecutionEvent(latest, event));
      try {
        await refreshRoom();
      } catch (error) {
        if (!mounted.current || isAbortError(error)) return;
        setCommandError(errorMessage(error));
      }
      return;
    }
    if (event.type === "MATCH_FINISHED") {
      const latest = roomRef.current;
      if (latest) commitRoom({ ...latest, matchStatus: "FINISHED" });
      try {
        await refreshRoom();
      } catch (error) {
        if (!mounted.current || isAbortError(error)) return;
        setCommandError(errorMessage(error));
      }
      return;
    }
    if (event.type === "ROOM_INITIALIZED" || event.type === "SERVER_RECOVERED" || event.type === "ROOM_UNAVAILABLE") {
      const beforeMatch = roomRef.current?.matchId ?? null;
      try {
        await refreshRoom();
      } catch (error) {
        if (!mounted.current || isAbortError(error)) return;
        setCommandError(errorMessage(error));
        return;
      }
      if (event.type === "ROOM_INITIALIZED" && beforeMatch && roomRef.current && roomRef.current.matchId !== beforeMatch) {
        await resubscribe(true);
      }
      return;
    }
    if (event.type === "PROMPT_PUBLISHED") {
      applyPublishedPayload(event);
      try {
        await refreshRoom();
        if (generation === sseGen.current) await maybeReloadAgent(event);
      } catch (error) {
        if (!mounted.current || isAbortError(error)) return;
      }
    }
  }

  function applyPublishedPayload(event: RoomEvent) {
    const agentId = event.payload.agentId;
    const versionNo = event.payload.versionNo;
    if (typeof agentId !== "string" || typeof versionNo !== "number" || !Number.isSafeInteger(versionNo)) return;
    const key = seatKeyForAgent(agentId);
    if (!key) return;
    commitSeats((prev) => {
      const seat = prev[key];
      if (!seat) return prev;
      return { ...prev, [key]: { ...seat, publishedVersionNo: versionNo } };
    });
  }

  async function maybeReloadAgent(event: RoomEvent) {
    const agentId = event.payload.agentId;
    if (typeof agentId !== "string") return;
    const key = seatKeyForAgent(agentId);
    if (!key) return;
    const seat = seatsRef.current[key];
    if (!seat || !isDraftClean(seat)) return;
    const view = await getAgent(agentId);
    if (!mounted.current) return;
    commitSeats((prev) => {
      const current = prev[key];
      if (!current) return prev;
      return { ...prev, [key]: applyAgentIfClean(current, view) };
    });
  }

  function seatKeyForAgent(agentId: string): SeatKey | null {
    if (seatsRef.current.A?.agentId === agentId) return "A";
    if (seatsRef.current.B?.agentId === agentId) return "B";
    return null;
  }

  async function loadInitial(seat: SeatKey, agentId: string, signal: AbortSignal, cancelled: () => boolean) {
    commitSeats((prev) => ({ ...prev, [seat]: loadingSeat(agentId) }));
    try {
      const view = await getAgent(agentId, signal);
      if (cancelled()) return;
      commitSeats((prev) => ({ ...prev, [seat]: seatFromAgent(view) }));
    } catch (error) {
      if (cancelled() || isAbortError(error)) return;
      commitSeats((prev) => ({
        ...prev,
        [seat]: { ...loadingSeat(agentId), error: errorMessage(error) },
      }));
    }
  }

  async function boot(signal: AbortSignal, cancelled: () => boolean) {
    setPageError(null);
    commitSse("connecting");
    try {
      const created = await ensureCurrentRoom(signal);
      if (cancelled()) return;
      const fetched = await getCurrentRoom(signal);
      if (cancelled()) return;
      const snapshot = mergeAuthoritativeRoom(asRoomSnapshot(created), fetched);
      commitRoom(snapshot);
      const seatA = findSeatAgentId(snapshot.participants, "A");
      const seatB = findSeatAgentId(snapshot.participants, "B");
      if (!seatA || !seatB) {
        setPageError("房间参与者缺少席位 A 或 B");
        commitSse("disconnected");
        return;
      }
      await Promise.all([
        loadInitial("A", seatA, signal, cancelled),
        loadInitial("B", seatB, signal, cancelled),
      ]);
      if (cancelled()) return;
      maxSeenRef.current = snapshot.eventCursor;
      healthConsumed.current = snapshot.health !== "OK";
      openSse(snapshot.roomId, snapshot.eventCursor);
    } catch (error) {
      if (cancelled() || isAbortError(error)) return;
      setPageError(errorMessage(error));
      commitSse("disconnected");
    }
  }

  async function roomForCommand(): Promise<RoomSnapshot | null> {
    const current = roomRef.current;
    if (!current) return null;
    if (current.seenRevision <= current.revision) return current;
    try {
      await refreshRoom();
    } catch (error) {
      if (isAbortError(error)) return null;
    }
    return roomRef.current;
  }

  async function saveSeat(seat: SeatKey, holdsLock = false): Promise<{ ok: true; revision: number } | { ok: false }> {
    if (!holdsLock && seatLock.current[seat]) return { ok: false };
    const current = seatsRef.current[seat];
    if (!current || current.phase !== "ready" || current.revision < 1) return { ok: false };
    if (!holdsLock) seatLock.current[seat] = true;
    const snapshot = current.content;
    const expected = current.revision;
    commitSeats((prev) => {
      const seatState = prev[seat];
      if (!seatState) return prev;
      return { ...prev, [seat]: { ...seatState, phase: "saving", error: null } };
    });
    try {
      const draft = await savePromptDraft(current.agentId, buildDraftUpdate({
        expectedDraftRevision: expected,
        content: snapshot,
      }));
      if (!mounted.current) return { ok: false };
      commitSeats((prev) => {
        const seatState = prev[seat];
        if (!seatState) return prev;
        return {
          ...prev,
          [seat]: {
            ...seatState,
            ...applyDraftSave({
              currentContent: seatState.content,
              responseContent: draft.content,
              responseRevision: draft.revision,
            }),
            phase: "ready",
            remote: null,
            error: null,
          },
        };
      });
      return { ok: true, revision: draft.revision };
    } catch (error) {
      if (!mounted.current || isAbortError(error)) return { ok: false };
      if (isApiError(error) && error.code === "DRAFT_REVISION_CONFLICT") {
        await showDraftConflict(seat, current.agentId);
        return { ok: false };
      }
      if (isTransportLoss(error)) {
        const reconciled = await reconcileSave(current.agentId, snapshot);
        if (reconciled) {
          commitSeats((prev) => {
            const seatState = prev[seat];
            if (!seatState) return prev;
            return {
              ...prev,
              [seat]: {
                ...seatState,
                ...applyDraftSave({
                  currentContent: seatState.content,
                  responseContent: reconciled.savedContent,
                  responseRevision: reconciled.revision,
                }),
                name: reconciled.name,
                publishedVersionNo: reconciled.publishedVersionNo,
                phase: "ready",
                remote: null,
                error: null,
              },
            };
          });
          return { ok: true, revision: reconciled.revision };
        }
      }
      commitSeats((prev) => {
        const seatState = prev[seat];
        if (!seatState) return prev;
        return { ...prev, [seat]: { ...seatState, phase: "ready", error: errorMessage(error) } };
      });
      return { ok: false };
    } finally {
      if (!holdsLock) seatLock.current[seat] = false;
    }
  }

  async function reconcileSave(agentId: string, sentContent: string) {
    try {
      const view = await getAgent(agentId);
      const reconciled = reconcileDraftAfterLoss({
        sentContent,
        serverContent: view.draft.content,
        serverRevision: view.draft.revision,
      });
      if (reconciled.kind !== "saved") return null;
      return {
        revision: reconciled.revision,
        savedContent: reconciled.savedContent,
        name: view.name,
        publishedVersionNo: view.publishedPrompt.versionNo,
      };
    } catch (error) {
      if (isAbortError(error)) return null;
      return null;
    }
  }

  async function showDraftConflict(seat: SeatKey, agentId: string) {
    try {
      const view = await getAgent(agentId);
      if (!mounted.current) return;
      commitSeats((prev) => {
        const seatState = prev[seat];
        if (!seatState) return prev;
        return {
          ...prev,
          [seat]: {
            ...seatState,
            phase: "ready",
            name: view.name,
            publishedVersionNo: view.publishedPrompt.versionNo,
            revision: view.draft.revision,
            remote: { revision: view.draft.revision, content: view.draft.content },
            error: `草稿冲突，远端修订号 ${view.draft.revision}`,
          },
        };
      });
    } catch (error) {
      if (!mounted.current || isAbortError(error)) return;
      commitSeats((prev) => {
        const seatState = prev[seat];
        if (!seatState) return prev;
        return { ...prev, [seat]: { ...seatState, phase: "ready", error: errorMessage(error) } };
      });
    }
  }

  async function publishSeat(seat: SeatKey) {
    if (seatLock.current[seat] || !mounted.current) return;
    seatLock.current[seat] = true;
    try {
    const saved = await saveSeat(seat, true);
    if (!saved.ok || !mounted.current) return;
    commitSeats((prev) => {
      const current = prev[seat];
      if (!current) return prev;
      return { ...prev, [seat]: { ...current, phase: "saving" } };
    });
    const currentRoom = await roomForCommand();
    const seatState = seatsRef.current[seat];
    if (!currentRoom || !seatState) return;
    const body = buildPublishPrompt({
      commandId: newCommandId(),
      expectedRoomRevision: currentRoom.revision,
      expectedMatchId: currentRoom.matchId,
      expectedDraftRevision: saved.revision,
    });
    try {
      const version = await postIdempotent(() => publishPrompt(currentRoom.roomId, seatState.agentId, body));
      if (!mounted.current) return;
      commitSeats((prev) => {
        const current = prev[seat];
        if (!current) return prev;
        return { ...prev, [seat]: { ...current, publishedVersionNo: version.versionNo, error: null } };
      });
      await refreshRoom();
    } catch (error) {
      if (!mounted.current || isAbortError(error)) return;
      if (isApiError(error) && error.code === "DRAFT_REVISION_CONFLICT") {
        await showDraftConflict(seat, seatState.agentId);
        return;
      }
      if (isTransportLoss(error)) {
        try {
          const view = await getAgent(seatState.agentId);
          if (!mounted.current) return;
          commitSeats((prev) => {
            const current = prev[seat];
            if (!current) return prev;
            return { ...prev, [seat]: { ...applyAgentIfClean(current, view), error: errorMessage(error) } };
          });
          await refreshRoom();
        } catch (refreshError) {
          if (!mounted.current || isAbortError(refreshError)) return;
          markSeatError(seat, errorMessage(error));
        }
        return;
      }
      markSeatError(seat, errorMessage(error));
      if (isApiError(error) && error.status === 409) {
        setCommandError(errorMessage(error));
        try {
          await refreshRoom();
        } catch (refreshError) {
          if (!mounted.current || isAbortError(refreshError)) return;
        }
      }
    }
  } finally {
    seatLock.current[seat] = false;
    if (mounted.current) {
      commitSeats((prev) => {
        const current = prev[seat];
        if (!current || current.phase !== "saving") return prev;
        return { ...prev, [seat]: { ...current, phase: "ready" } };
      });
    }
  }
  }

  function markSeatError(seat: SeatKey, message: string) {
    commitSeats((prev) => {
      const current = prev[seat];
      if (!current) return prev;
      return { ...prev, [seat]: { ...current, phase: "ready", error: message } };
    });
  }

  function loadRemote(seat: SeatKey) {
    commitSeats((prev) => {
      const current = prev[seat];
      if (!current?.remote || current.phase === "loading") return prev;
      return {
        ...prev,
        [seat]: {
          ...current,
          content: current.remote.content,
          savedContent: current.remote.content,
          revision: current.remote.revision,
          remote: null,
          error: null,
          phase: "ready",
        },
      };
    });
  }

  function editSeat(seat: SeatKey, content: string) {
    commitSeats((prev) => {
      const current = prev[seat];
      if (!current || current.phase === "loading") return prev;
      return { ...prev, [seat]: { ...current, content } };
    });
  }

  async function sendControl(type: ControlKey) {
    if (commandLock.current || !mounted.current) return;
    commandLock.current = true;
    setCommandPending(true);
    setCommandError(null);
    let beforeMatchId: string | null = null;
    try {
    const current = await roomForCommand();
    if (!mounted.current || !current) return;
    const held = controlAvailability({
      matchStatus: current.matchStatus,
      executionStatus: current.executionStatus,
      health: current.health,
      sse: sseRef.current,
      commandPending: false,
      commandUncertain,
    });
    const resolved = controlAvailability({
      matchStatus: current.matchStatus,
      executionStatus: current.executionStatus,
      health: current.health,
      sse: sseRef.current,
      commandPending: false,
      commandUncertain: false,
    });
    if (!held[type] && !uncertainClickSends(type, commandUncertain, resolved)) return;
    if ((type === "start" || type === "step") && (sseRef.current !== "open" || current.health !== "OK")) return;
    beforeMatchId = current.matchId;
    const acknowledging = uncertainClickSends(type, commandUncertain, resolved);
    if (acknowledging) setCommandUncertain(false);
    const body = buildControlCommand({
      commandId: newCommandId(),
      expectedRoomRevision: current.revision,
      expectedMatchId: current.matchId,
      type,
    });
      const result = await postIdempotent(() => postCommand(current.roomId, body));
      if (!mounted.current) return;
      const latest = roomRef.current;
      if (latest) commitRoom(applyCommandAck(latest, result));
      if (type === "reset") {
        const refreshed = await resubscribe(true);
        if (resetResultKnown(beforeMatchId, refreshed?.matchId ?? null)) setCommandUncertain(false);
      }
    } catch (error) {
      if (!mounted.current || isAbortError(error)) return;
      setCommandError(errorMessage(error));
      if (isTransportLoss(error)) setCommandUncertain(true);
      try {
        const refreshed = await refreshRoom();
        if (!mounted.current) return;
        if (type === "reset" && resetResultKnown(beforeMatchId, refreshed?.matchId ?? null)) {
          setCommandUncertain(false);
          await resubscribe(true);
        }
      } catch (refreshError) {
        if (!mounted.current || isAbortError(refreshError)) return;
        if (!isTransportLoss(error)) setCommandError(errorMessage(refreshError));
      }
    } finally {
      commandLock.current = false;
      if (mounted.current) setCommandPending(false);
    }
  }

  function reconnect() {
    autoResubscribes.current = 0;
    failures.current = 0;
    setSseGaveUp(false);
    void resubscribe(false);
  }

  actions.current = { boot, stopSse };

  useEffect(() => {
    mounted.current = true;
    const ac = new AbortController();
    let cancelled = false;
    void actions.current?.boot(ac.signal, () => cancelled);
    return () => {
      cancelled = true;
      ac.abort();
      actions.current?.stopSse();
      mounted.current = false;
    };
  }, []);

  const availability = controlAvailability({
    matchStatus: room?.matchStatus ?? null,
    executionStatus: room?.executionStatus ?? null,
    health: room?.health ?? null,
    sse,
    commandPending,
    commandUncertain,
  });
  const acknowledge = uncertainAcknowledge(commandUncertain, controlAvailability({
    matchStatus: room?.matchStatus ?? null,
    executionStatus: room?.executionStatus ?? null,
    health: room?.health ?? null,
    sse,
    commandPending,
    commandUncertain: false,
  }));

  return {
    room,
    seats,
    events,
    sse,
    sseGaveUp,
    pageError,
    commandError,
    commandUncertain,
    availability,
    acknowledge,
    editSeat,
    saveSeat,
    publishSeat,
    loadRemote,
    sendControl,
    reconnect,
  };
}
