import { apiClient } from './client'
import type {
  CompleteMultipartBody,
  MultipartStart,
  PartUrl,
  RecordingStreamView,
  StreamType,
} from '../types/recording'

/**
 * Recording multipart orchestration (02 §4.3, 05B §4).
 *
 * Media bytes do not pass through the application server: the browser receives
 * a presigned MinIO URL per part and PUTs the blob directly. These calls only
 * manage the multipart upload lifecycle.
 */

function multipartPath(sessionId: string, streamType: StreamType, action: string): string {
  return `/sessions/${encodeURIComponent(sessionId)}/recordings/${encodeURIComponent(
    streamType,
  )}/multipart/${action}`
}

export function startMultipart(
  sessionId: string,
  streamType: StreamType,
): Promise<MultipartStart> {
  return apiClient
    .post<MultipartStart>(multipartPath(sessionId, streamType, 'start'))
    .then((response) => response.data)
}

export function partUrl(
  sessionId: string,
  streamType: StreamType,
  partNumber: number,
): Promise<PartUrl> {
  return apiClient
    .post<PartUrl>(multipartPath(sessionId, streamType, 'part-url'), {
      part_number: partNumber,
    })
    .then((response) => response.data)
}

export function completeMultipart(
  sessionId: string,
  streamType: StreamType,
  body: CompleteMultipartBody,
): Promise<RecordingStreamView> {
  return apiClient
    .post<RecordingStreamView>(multipartPath(sessionId, streamType, 'complete'), body)
    .then((response) => response.data)
}

/** The backend returns 204 No Content for abort. */
export async function abortMultipart(
  sessionId: string,
  streamType: StreamType,
): Promise<void> {
  await apiClient.post(multipartPath(sessionId, streamType, 'abort'))
}

/**
 * Adapter shape consumed by `RecorderUploader`. Keeping the functions in one
 * object makes the uploader easy to unit-test with a fake lifecycle.
 */
export const recordingsApi = {
  startMultipart,
  partUrl,
  completeMultipart,
  abortMultipart,
}