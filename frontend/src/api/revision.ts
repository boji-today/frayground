/** Incoming revision may replace state when it is not older than the newest revision already seen. */
export function shouldApplyRevision(known: number, incoming: number): boolean {
  return incoming >= known;
}
