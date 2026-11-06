import { apiClient } from './client'

/**
 * Fetches a one-time control-channel ticket (02 §6, 05A §8).
 *
 * Tickets are valid for 30 seconds; `createWsClient` requests a fresh one for
 * every initial connection and every reconnect attempt.
 */
export async function fetchWsTicket(sessionId: string): Promise<string> {
  const response = await apiClient.post<{ ticket: string }>(
    `/sessions/${encodeURIComponent(sessionId)}/ws-ticket`,
  )
  return response.data.ticket
}