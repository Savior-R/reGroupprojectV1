import { apiClient } from './client'
import type { SessionView } from '../types/session'

/**
 * Session lifecycle API (02 §4.3). The WebSocket control channel is the
 * primary start/end path in the rooms; these REST calls are the shared
 * fallback and the only way to create a task session.
 */

export function createSession(taskId: string): Promise<SessionView> {
  return apiClient
    .post<SessionView>(`/tasks/${encodeURIComponent(taskId)}/sessions`)
    .then((response) => response.data)
}

export function getSession(sessionId: string): Promise<SessionView> {
  return apiClient
    .get<SessionView>(`/sessions/${encodeURIComponent(sessionId)}`)
    .then((response) => response.data)
}

export function startSession(sessionId: string): Promise<SessionView> {
  return apiClient
    .post<SessionView>(`/sessions/${encodeURIComponent(sessionId)}/start`)
    .then((response) => response.data)
}

export function endSession(sessionId: string): Promise<SessionView> {
  return apiClient
    .post<SessionView>(`/sessions/${encodeURIComponent(sessionId)}/end`)
    .then((response) => response.data)
}

/** The backend returns 204 No Content for cancel. */
export async function cancelSession(sessionId: string): Promise<void> {
  await apiClient.post(`/sessions/${encodeURIComponent(sessionId)}/cancel`)
}