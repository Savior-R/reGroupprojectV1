/**
 * Recording / multipart / replay wire types (05B §3–§7, 02 §4.3).
 *
 * All field names are snake_case because the backend serializes DTOs with the
 * global SNAKE_CASE strategy.
 */

export type StreamType = 'camera' | 'screen' | 'audio'

/** Durable stream state on the server (`recording_streams.status`). */
export type StreamStatus = 'uploading' | 'uploaded' | 'failed' | 'deleted'

/** Whole-recording state (`recordings.status`). */
export type RecordingStatus = 'recording' | 'uploading' | 'uploaded' | 'failed' | 'deleted'

/** One recording stream as exposed by `GET /sessions/{id}/recordings`. */
export interface RecordingStreamView {
  stream_type: StreamType
  status: StreamStatus
  upload_id: string | null
  object_key: string | null
  parts_uploaded: number
  bytes_uploaded: number
  duration_ms: number | null
  offset_ms: number
  mime: string | null
  part_size: number
}

/** `POST /sessions/{id}/recordings/{streamType}/multipart/start` response. */
export interface MultipartStart {
  upload_id: string
  object_key: string
  part_size: number
}

/** `POST .../multipart/part-url` response. */
export interface PartUrl {
  url: string
  part_number: number
  expires_in_seconds: number
}

export interface RecordedPart {
  part_number: number
  etag: string
}

/** `POST .../multipart/complete` request body. */
export interface CompleteMultipartBody {
  parts: RecordedPart[]
  duration_ms: number
  size: number
  mime: string
  /** Stream start relative to the attempt's t0 (earliest stream start). */
  offset_ms: number
}

/** One presigned replay stream. */
export interface ReplayStreamView {
  stream_type: StreamType
  url: string
  offset_ms: number
  duration_ms: number
  mime: string
}

/** `GET /sessions/{id}/replay` response. */
export interface ReplayView {
  session_id: string
  mode: 'free' | 'scenario' | 'interview'
  duration_ms: number
  streams: ReplayStreamView[]
}