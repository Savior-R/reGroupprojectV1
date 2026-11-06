import { apiClient } from './client'

export interface PerceptionScreenResponse {
  frame_id: string
  status: string
}

/**
 * Uploads one periodic screen JPEG for asynchronous OCR (05A §6.2, 02 §4.3).
 * The backend returns 202 with the pending frame id; OCR failures only mark the
 * frame failed and never stop recording.
 */
export function uploadScreen(
  sessionId: string,
  jpeg: Blob,
  atMs: number,
  changeScore: number,
): Promise<PerceptionScreenResponse> {
  const form = new FormData()
  form.append('file', jpeg, 'screen.jpg')
  form.append('at_ms', String(Math.max(0, Math.floor(atMs))))
  form.append('change_score', changeScore.toFixed(3))
  return apiClient
    .post<PerceptionScreenResponse>(
      `/sessions/${encodeURIComponent(sessionId)}/perception/screen`,
      form,
    )
    .then((response) => response.data)
}
