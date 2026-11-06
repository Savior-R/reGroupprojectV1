import { apiClient } from './client'
import type { PageResult } from '../types/api'
import type { RecordingStreamView, ReplayView } from '../types/recording'

/**
 * Replay / review API (02 §4.3, 05B §7).
 *
 * The replay manifest only carries short-lived presigned GET URLs; callers must
 * refetch it when the URLs expire (~15 min) or a media element reports an
 * error. `getRecordings` is exposed here because the review page polls it to
 * show upload progress before the replay manifest becomes available.
 */

export type SpeakerType = 'teacher' | 'student' | 'examiner' | 'candidate'

/** One persisted event row returned by `GET /sessions/{id}/events`. */
export interface ReplayEvent {
  event_id: string
  seq: number
  type: string
  at_ms: number
  payload: Record<string, unknown>
}

/** One row of the unified text stream (`GET /sessions/{id}/texts`). */
export interface SessionText {
  id: string
  speaker_type: string
  speaker_ref: string | null
  source: string
  kind: string
  text: string
  start_ms: number | null
  end_ms: number | null
  at_ms: number
  confidence: number | null
  status: string
}

/** `POST /sessions/{id}/submit` response. */
export interface SubmissionView {
  submission_id: string
  task_id: string
  session_id: string
  student_account_id: string
  status: string
  submitted_at: string
}

export function getReplay(sessionId: string): Promise<ReplayView> {
  return apiClient
    .get<ReplayView>(`/sessions/${encodeURIComponent(sessionId)}/replay`)
    .then((response) => response.data)
}

export function getEvents(
  sessionId: string,
  afterSeq = 0,
  limit = 100,
): Promise<PageResult<ReplayEvent>> {
  return apiClient
    .get<PageResult<ReplayEvent>>(`/sessions/${encodeURIComponent(sessionId)}/events`, {
      params: { after_seq: afterSeq, limit },
    })
    .then((response) => response.data)
}

export function getTexts(sessionId: string, speakerType?: SpeakerType): Promise<SessionText[]> {
  return apiClient
    .get<SessionText[]>(`/sessions/${encodeURIComponent(sessionId)}/texts`, {
      params: speakerType ? { speaker_type: speakerType } : undefined,
    })
    .then((response) => response.data)
}

export function getRecordings(sessionId: string): Promise<RecordingStreamView[]> {
  return apiClient
    .get<RecordingStreamView[]>(`/sessions/${encodeURIComponent(sessionId)}/recordings`)
    .then((response) => response.data)
}

export function submitSession(sessionId: string): Promise<SubmissionView> {
  return apiClient
    .post<SubmissionView>(`/sessions/${encodeURIComponent(sessionId)}/submit`)
    .then((response) => response.data)
}