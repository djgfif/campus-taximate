/**
 * A single EventSource lives in App; individual chat panels subscribe to room-specific
 * notifications without opening duplicate SSE connections.
 */
const listeners = new Set()

export function subscribeChatUpdates(listener) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function announceChatUpdate(partyId = null) {
  for (const listener of listeners) listener(partyId)
}
