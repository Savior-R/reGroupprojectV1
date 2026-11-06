/**
 * Session / WebSocket wire types (02 §6, 05A §6/§8, 05C).
 *
 * Field names stay snake_case to match the backend JSON. The types expose only
 * student-visible data: no rubric weights, answer points, reference material or
 * virtual-student parameters/personality.
 */

import type { ClientCommandType, EventPayload, ServerEventType } from './event'
import type { StreamType } from './recording'

export type SessionStatus =
  | 'preparing'
  | 'in_progress'
  | 'ended'
  | 'reviewing'
  | 'submitted'
  | 'cancelled'
  | 'failed'

export type SessionMode = 'free' | 'scenario' | 'interview'

export type SessionType = 'task' | 'training'

export type VirtualStudentState =
  | 'focused'
  | 'distracted'
  | 'whispering'
  | 'hand_raised'
  | 'called_on'
  | 'answering'
  | 'dialogue'

/** Public task configuration copied into the session snapshot. */
export interface PublicSessionConfig {
  topic?: string
  textbook_version?: string
  recommended_seconds?: number
  max_seconds?: number
  scenario?: {
    role?: string
    situation?: string
  }
  [key: string]: unknown
}

/** Student-visible virtual-student runtime row. */
export interface VirtualStudentSnapshot {
  virtual_student_id: string
  name: string
  avatar?: string | null
  seat_no?: number
  sort_order?: number
  state: VirtualStudentState
  attention: number
  hand_raise_energy: number
}

export interface InterviewQuestionSnapshot {
  question_id: string
  order: number
  total: number
  text: string
}

/** `session.snapshot.payload.interview`; empty object outside interview mode. */
export interface InterviewSnapshot {
  current?: InterviewQuestionSnapshot | null
  answer_id?: string | null
  remaining_ms?: number
  ended?: boolean
}

/** `session.snapshot.payload.recording`. */
export interface RecordingSnapshot {
  required_streams: StreamType[]
  streams: Partial<Record<StreamType, string>>
  ready: boolean
}

/** `session.snapshot.payload`. */
export interface SessionSnapshot {
  state: SessionStatus
  mode: SessionMode
  public_config: PublicSessionConfig
  virtual_students: VirtualStudentSnapshot[]
  interview: InterviewSnapshot
  recording: RecordingSnapshot
  last_seq: number
  elapsed_ms: number
}

/** `GET /sessions/{id}` and session actions response (02 §4.3). */
export interface SessionView {
  session_id: string
  task_id: string | null
  course_id: string | null
  session_type: SessionType
  mode: SessionMode
  status: SessionStatus
  attempt_no: number
  started_at: string | null
  ended_at: string | null
  duration_ms: number | null
  stream_offsets: Partial<Record<StreamType, number>>
  last_seq: number
}

/** Server -> client event envelope (02 §6.2). */
export interface ServerEvent<P = EventPayload> {
  v: number
  type: ServerEventType
  event_id: string
  seq: number
  at_ms: number
  session_id: string
  payload: P
}

/** Client -> server command envelope (02 §6.2). */
export interface ClientCommand<P = EventPayload> {
  v: number
  type: ClientCommandType
  command_id: string
  payload: P
}