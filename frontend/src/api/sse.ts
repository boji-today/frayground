import { parseRoomEventData } from "./parse";
import { ROOM_EVENT_TYPES, type RoomEvent } from "./types";

export function subscribeRoomEvents(roomId: string, after: number, handlers: {
  onOpen: () => void;
  onEvent: (event: RoomEvent) => void;
  onError: (info: { readyState: number }) => void;
}): () => void {
  const source = new EventSource(`/api/v1/rooms/${encodeURIComponent(roomId)}/events?after=${after}`);
  let closedByUs = false;
  source.onopen = () => {
    if (!closedByUs) handlers.onOpen();
  };
  for (const type of ROOM_EVENT_TYPES) {
    source.addEventListener(type, (message) => {
      if (closedByUs || !(message instanceof MessageEvent) || typeof message.data !== "string") return;
      const parsed = parseRoomEventData(message.data);
      if (parsed) handlers.onEvent(parsed);
    });
  }
  source.onerror = () => {
    if (closedByUs) return;
    handlers.onError({ readyState: source.readyState });
  };
  return () => {
    closedByUs = true;
    source.close();
  };
}
