import fixWebmDuration from 'fix-webm-duration'
import type {
  CompleteMultipartBody,
  MultipartStart,
  PartUrl,
  RecordingStreamView,
  StreamType,
} from '../types/recording'
import { STREAM_MIME } from './media'

/**
 * MediaRecorder -> MinIO multipart uploader (05B §4).
 *
 * The uploader is framework-free and every external effect (API adapter,
 * `fetch`, wall clock, `fix-webm-duration`) is injectable so the buffering and
 * failure state machine can be unit-tested without a browser.
 *
 * Flow:
 * 0. The recorder is already running when the uploader is constructed, so
 *    recorder/track listeners are attached immediately and `startedAt` is
 *    sampled there; early `dataavailable` blobs are buffered and must survive
 *    the `recording.ready_ack` round trip.
 * 1. `multipart/start` returns the 5 MB part size; buffered full parts are
 *    flushed as soon as multipart exists.
 * 2. `dataavailable` blobs accumulate; every full part is PUT to its presigned
 *    URL (each part is retried up to `MAX_PART_ATTEMPTS` times).
 * 3. Part 1 is retained. On stop it is patched with `fix-webm-duration` and
 *    re-uploaded under the same part number so the new ETag replaces the old.
 * 4. The remaining tail is uploaded as the last part, then `multipart/complete`
 *    receives every part's latest ETag plus duration/size/mime.
 *
 * A backlog above `MAX_BUFFERED_BYTES`, an exhausted retry, a track ending, or
 * a duration-patch failure aborts the multipart and makes the stream fail.
 */

export const DEFAULT_PART_SIZE = 5 * 1024 * 1024
export const MAX_BUFFERED_BYTES = 30 * 1024 * 1024
export const MAX_PART_ATTEMPTS = 3

export type UploadStatus = 'uploading' | 'completed' | 'failed'

export interface UploadProgress {
  uploadedBytes: number
  totalBytes: number
  status: UploadStatus
}

export type UploadFailureReason =
  | 'start_failed'
  | 'backlog'
  | 'part_failed'
  | 'duration_patch'
  | 'track_ended'
  | 'recorder_error'
  | 'complete_failed'
  | 'empty_recording'
  | 'aborted'

export class RecordingUploadError extends Error {
  readonly reason: UploadFailureReason

  constructor(
    reason: UploadFailureReason,
    message: string,
    options?: { cause?: unknown },
  ) {
    super(message, options)
    this.name = 'RecordingUploadError'
    this.reason = reason
  }
}

/** Camel-case part record used inside the uploader and by callers. */
export interface RecordedPart {
  partNumber: number
  etag: string
}

/** Minimal response shape needed from the presigned MinIO PUT. */
export interface UploadResponseLike {
  ok: boolean
  status?: number
  headers: { get(name: string): string | null }
}

export type UploadFetch = (url: string, init: RequestInit) => Promise<UploadResponseLike>

export type FixWebmDuration = (blob: Blob, durationMs: number) => Promise<Blob>

export interface RecorderUploaderApi {
  startMultipart(sessionId: string, streamType: StreamType): Promise<MultipartStart>
  partUrl(
    sessionId: string,
    streamType: StreamType,
    partNumber: number,
  ): Promise<PartUrl>
  completeMultipart(
    sessionId: string,
    streamType: StreamType,
    body: CompleteMultipartBody,
  ): Promise<RecordingStreamView>
  abortMultipart(sessionId: string, streamType: StreamType): Promise<void>
}

export interface TrackLike {
  readonly readyState?: string
  addEventListener(type: 'ended', listener: () => void): void
  removeEventListener?(type: 'ended', listener: () => void): void
}

export interface RecorderStreamLike {
  getTracks(): TrackLike[]
}

/** Subset of the browser `MediaRecorder` used by the uploader. */
export interface RecorderLike {
  readonly mimeType?: string
  readonly state?: string
  readonly stream?: RecorderStreamLike
  addEventListener(type: string, listener: (event: any) => void): void
  removeEventListener?(type: string, listener: (event: any) => void): void
  stop(): void
}

export interface RecorderUploaderOptions {
  /** Test seam: defaults to the global `fetch`. */
  fetch?: UploadFetch
  /** Test seam: defaults to `Date.now`. */
  now?: () => number
  /** Test seam: defaults to the `fix-webm-duration` default export. */
  fixWebmDuration?: FixWebmDuration
  /** Defaults to 30 MB. */
  maxBufferedBytes?: number
  /** Defaults to 3 (total PUT attempts per part). */
  maxPartAttempts?: number
  onProgress?: (progress: UploadProgress) => void
  onError?: (error: RecordingUploadError) => void
}

function defaultFetch(url: string, init: RequestInit): Promise<UploadResponseLike> {
  return globalThis.fetch(url, init)
}

const defaultFixWebmDuration: FixWebmDuration = (blob, durationMs) =>
  fixWebmDuration(blob, durationMs)

export class RecorderUploader {
  private readonly api: RecorderUploaderApi
  private readonly sessionId: string
  private readonly streamType: StreamType
  private readonly recorder: RecorderLike
  private readonly fetchImpl: UploadFetch
  private readonly now: () => number
  private readonly fixDuration: FixWebmDuration
  private readonly maxBufferedBytes: number
  private readonly maxPartAttempts: number

  private readonly progressListeners = new Set<(progress: UploadProgress) => void>()
  private readonly errorListeners = new Set<(error: RecordingUploadError) => void>()
  private readonly tracks: TrackLike[] = []

  private state: 'idle' | 'recording' | 'stopping' | 'completed' | 'failed' = 'idle'
  private failure: RecordingUploadError | null = null
  private started = false
  private startedAt = 0
  private stoppedAt: number | null = null
  private startPromise: Promise<void> | null = null
  private multipartPromise: Promise<MultipartStart> | null = null
  private partSize = DEFAULT_PART_SIZE
  private buffer: Blob[] = []
  private bufferBytes = 0
  private pendingBytes = 0
  private receivedBytes = 0
  private nextPartNumber = 1
  private firstPartBlob: Blob | null = null
  private readonly parts = new Map<number, RecordedPart>()
  private readonly partSizes = new Map<number, number>()
  private flushPromise: Promise<void> | null = null
  private stopPromise: Promise<RecordingStreamView> | null = null
  private stopSignal: Promise<void> | null = null
  private resolveStopSignal: (() => void) | null = null
  private stopSeen = false
  private abortPromise: Promise<void> | null = null

  constructor(
    api: RecorderUploaderApi,
    sessionId: string,
    streamType: StreamType,
    recorder: RecorderLike,
    options: RecorderUploaderOptions = {},
  ) {
    this.api = api
    this.sessionId = sessionId
    this.streamType = streamType
    this.recorder = recorder
    this.fetchImpl = options.fetch ?? defaultFetch
    this.now = options.now ?? (() => Date.now())
    this.fixDuration = options.fixWebmDuration ?? defaultFixWebmDuration
    this.maxBufferedBytes = options.maxBufferedBytes ?? MAX_BUFFERED_BYTES
    this.maxPartAttempts = options.maxPartAttempts ?? MAX_PART_ATTEMPTS
    if (options.onProgress) {
      this.onProgress(options.onProgress)
    }
    if (options.onError) {
      this.onError(options.onError)
    }

    // The MediaRecorder is already running when the uploader is constructed
    // (see `createRecorder`). Attach listeners and sample `startedAt` here so
    // the first 5 s `dataavailable` blob is never dropped while the page waits
    // for `recording.ready_ack`.
    this.startedAt = this.now()
    this.stopSignal = new Promise<void>((resolve) => {
      this.resolveStopSignal = resolve
    })
    this.attachListeners()
  }

  /** Starts multipart upload. The recorder must already be recording. */
  start(): Promise<void> {
    if (!this.startPromise) {
      this.startPromise = this.begin()
    }
    return this.startPromise
  }

  stop(): Promise<RecordingStreamView> {
    if (!this.stopPromise) {
      this.stopPromise = this.finish()
    }
    return this.stopPromise
  }

  /**
   * Aborts the multipart upload exactly once. Calling it after a failure is a
   * no-op (the failure path already issued the abort request).
   */
  abort(): Promise<void> {
    if (this.state === 'completed') {
      return Promise.resolve()
    }
    if (!this.failure) {
      const failure = new RecordingUploadError('aborted', '上传已取消')
      this.failure = failure
      this.state = 'failed'
      this.emitProgress('failed')
      for (const listener of this.errorListeners) {
        listener(failure)
      }
    }
    return this.ensureAbort()
  }

  onProgress(callback: (progress: UploadProgress) => void): void {
    this.progressListeners.add(callback)
  }

  onError(callback: (error: RecordingUploadError) => void): void {
    this.errorListeners.add(callback)
  }

  private async begin(): Promise<void> {
    this.started = true
    if (this.failure) {
      throw this.failure
    }

    this.state = 'recording'
    this.emitProgress('uploading')

    this.multipartPromise = this.api.startMultipart(this.sessionId, this.streamType)
    try {
      const info = await this.multipartPromise
      if (info.part_size > 0) {
        this.partSize = info.part_size
      }
    } catch (error) {
      throw this.fail('start_failed', '无法开始分片上传', error)
    }

    // Blobs emitted between construction and start() are already buffered;
    // flush any complete parts now that multipart exists.
    void this.flushBuffer()
  }

  private readonly handleDataAvailable = (event: any): void => {
    if (this.failure || this.state === 'completed') {
      return
    }
    const data = event?.data
    if (!(data instanceof Blob) || data.size <= 0) {
      return
    }

    this.buffer.push(data)
    this.bufferBytes += data.size
    this.receivedBytes += data.size
    this.pendingBytes += data.size
    this.emitProgress('uploading')

    if (this.pendingBytes > this.maxBufferedBytes) {
      this.fail(
        'backlog',
        `上传缓冲积压超过 ${Math.round(this.maxBufferedBytes / (1024 * 1024))} MB`,
      )
      return
    }

    // Before start() there is no multipart upload to put parts into; the
    // buffer is flushed once multipart starts (or on stop()).
    if (this.started) {
      void this.flushBuffer()
    }
  }

  private readonly handleRecorderStop = (): void => {
    // The recording ends here. Upload latency from the final flush must not be
    // counted as recording time, nor passed to `fix-webm-duration`.
    if (this.stoppedAt === null) {
      this.stoppedAt = this.now()
    }
    this.stopSeen = true
    const resolve = this.resolveStopSignal
    this.resolveStopSignal = null
    resolve?.()
  }

  private readonly handleRecorderError = (event: any): void => {
    this.fail('recorder_error', '录制器发生错误', event)
  }

  private readonly handleTrackEnded = (): void => {
    this.fail('track_ended', '媒体轨道已结束，录制失败')
  }

  private attachListeners(): void {
    this.recorder.addEventListener('dataavailable', this.handleDataAvailable)
    this.recorder.addEventListener('stop', this.handleRecorderStop)
    this.recorder.addEventListener('error', this.handleRecorderError)
    this.attachTrackListeners()
  }

  private attachTrackListeners(): void {
    const stream = this.recorder.stream
    if (!stream) {
      return
    }
    for (const track of stream.getTracks()) {
      if (track.readyState === 'ended') {
        this.handleTrackEnded()
        continue
      }
      track.addEventListener('ended', this.handleTrackEnded)
      this.tracks.push(track)
    }
  }

  private detachListeners(): void {
    this.recorder.removeEventListener?.('dataavailable', this.handleDataAvailable)
    this.recorder.removeEventListener?.('stop', this.handleRecorderStop)
    this.recorder.removeEventListener?.('error', this.handleRecorderError)
    for (const track of this.tracks) {
      track.removeEventListener?.('ended', this.handleTrackEnded)
    }
    this.tracks.length = 0
  }

  private async getPartSize(): Promise<number> {
    const pending = this.multipartPromise
    if (pending) {
      try {
        await pending
      } catch {
        // `begin()` surfaces the failure; the flush loop stops on it.
      }
    }
    return this.partSize
  }

  private flushBuffer(): Promise<void> {
    if (!this.flushPromise) {
      this.flushPromise = this.runFlush()
    }
    return this.flushPromise
  }

  private async runFlush(): Promise<void> {
    try {
      while (!this.failure) {
        const size = await this.getPartSize()
        if (this.failure || this.bufferBytes < size) {
          break
        }
        const partNumber = this.nextPartNumber
        const blob = this.takeBytes(size)
        this.nextPartNumber = partNumber + 1
        if (partNumber === 1) {
          this.firstPartBlob = blob
        }
        await this.putPart(partNumber, blob, true)
      }
    } catch (error) {
      this.fail('part_failed', '分片上传失败', error)
    } finally {
      this.flushPromise = null
    }
  }

  private takeBytes(size: number): Blob {
    const pieces: Blob[] = []
    let remaining = size
    while (remaining > 0 && this.buffer.length > 0) {
      const head = this.buffer[0]!
      if (head.size <= remaining) {
        pieces.push(head)
        this.buffer.shift()
        remaining -= head.size
      } else {
        pieces.push(head.slice(0, remaining))
        this.buffer[0] = head.slice(remaining)
        remaining = 0
      }
    }
    this.bufferBytes -= size
    return new Blob(pieces, { type: this.baseMime })
  }

  private takeRemaining(): Blob {
    const pieces = this.buffer
    this.buffer = []
    this.bufferBytes = 0
    return new Blob(pieces, { type: this.baseMime })
  }

  private async putPart(
    partNumber: number,
    blob: Blob,
    countPending: boolean,
  ): Promise<void> {
    let lastError: unknown
    for (let attempt = 1; attempt <= this.maxPartAttempts; attempt += 1) {
      if (this.failure) {
        throw this.failure
      }
      try {
        const part = await this.api.partUrl(this.sessionId, this.streamType, partNumber)
        const response = await this.fetchImpl(part.url, { method: 'PUT', body: blob })
        if (!response.ok) {
          throw new Error(
            `分片 ${partNumber} 上传失败 (HTTP ${response.status ?? 'unknown'})`,
          )
        }
        const etag = response.headers.get('ETag')
        if (etag === null || etag.trim() === '') {
          throw new Error(`分片 ${partNumber} 响应缺少 ETag`)
        }

        this.parts.set(partNumber, { partNumber, etag })
        this.partSizes.set(partNumber, blob.size)
        if (countPending) {
          this.pendingBytes = Math.max(0, this.pendingBytes - blob.size)
        }
        this.emitProgress('uploading')
        return
      } catch (error) {
        lastError = error
      }
    }
    throw lastError instanceof Error
      ? lastError
      : new Error(`分片 ${partNumber} 上传失败`)
  }

  private async putPartGuarded(partNumber: number, blob: Blob): Promise<void> {
    try {
      await this.putPart(partNumber, blob, false)
    } catch (error) {
      throw this.fail('part_failed', '分片上传失败', error)
    }
  }

  private async patchDuration(blob: Blob, durationMs: number): Promise<Blob> {
    try {
      const patched = await this.fixDuration(blob, durationMs)
      if (!(patched instanceof Blob) || patched.size <= 0) {
        throw new Error('fix-webm-duration 返回了空 Blob')
      }
      return patched
    } catch (error) {
      throw this.fail('duration_patch', '写入录制时长失败', error)
    }
  }

  private async stopRecorder(): Promise<void> {
    if (this.stopSeen) {
      return
    }
    if (this.recorder.state !== 'inactive') {
      try {
        this.recorder.stop()
      } catch {
        // If stop() refuses, waiting for the queued stop event still settles.
      }
    }
    if (!this.stopSeen) {
      await this.stopSignal
    }
    if (this.stoppedAt === null) {
      this.stoppedAt = this.now()
    }
  }

  private async finishInternal(): Promise<RecordingStreamView> {
    if (!this.started) {
      throw new RecordingUploadError('start_failed', '录制尚未开始')
    }
    if (this.failure) {
      throw this.failure
    }

    this.state = 'stopping'
    await this.stopRecorder()
    if (this.stoppedAt === null) {
      this.stoppedAt = this.now()
    }
    if (this.failure) {
      throw this.failure
    }

    if (this.multipartPromise) {
      try {
        await this.multipartPromise
      } catch {
        throw this.failure ?? new RecordingUploadError('start_failed', '无法开始分片上传')
      }
    }
    if (this.failure) {
      throw this.failure
    }

    // A slow uploader may still have a full part waiting in the buffer.
    await this.flushBuffer()
    if (this.failure) {
      throw this.failure
    }

    // Use the recorder's stop timestamp, never the post-upload clock: a slow
    // final flush must not inflate the duration written into part 1.
    const stoppedAt = this.stoppedAt ?? this.now()
    const durationMs = Math.max(1, Math.round(stoppedAt - this.startedAt))

    if (this.nextPartNumber === 1) {
      // The whole recording fits in part 1: patch it and upload it once.
      const tail = this.takeRemaining()
      if (tail.size <= 0) {
        throw this.fail('empty_recording', '没有录制数据')
      }
      const patched = await this.patchDuration(tail, durationMs)
      await this.putPartGuarded(1, patched)
      this.nextPartNumber = 2
    } else {
      const firstPart = this.firstPartBlob
      if (!firstPart) {
        throw this.fail('part_failed', '缺少第一片录制数据')
      }
      const patched = await this.patchDuration(firstPart, durationMs)
      await this.putPartGuarded(1, patched)

      const tail = this.takeRemaining()
      if (tail.size > 0) {
        const tailPartNumber = this.nextPartNumber
        this.nextPartNumber = tailPartNumber + 1
        await this.putPartGuarded(tailPartNumber, tail)
      }
    }

    // A track/recorder failure can arrive while the tail PUT is in flight.
    // Never complete a stream that has already been marked failed.
    if (this.failure) {
      throw this.failure
    }

    const body: CompleteMultipartBody = {
      parts: [...this.parts.values()]
        .sort((left, right) => left.partNumber - right.partNumber)
        .map((part) => ({ part_number: part.partNumber, etag: part.etag })),
      duration_ms: durationMs,
      size: this.sumPartSizes(),
      mime: this.completeMime,
    }

    try {
      const view = await this.api.completeMultipart(
        this.sessionId,
        this.streamType,
        body,
      )
      this.state = 'completed'
      this.receivedBytes = body.size
      this.emitProgress('completed')
      return view
    } catch (error) {
      throw this.fail('complete_failed', '完成分片上传失败', error)
    }
  }

  private async finish(): Promise<RecordingStreamView> {
    try {
      return await this.finishInternal()
    } catch (error) {
      const failure =
        this.failure ??
        (error instanceof RecordingUploadError
          ? this.fail(error.reason, error.message, error)
          : this.fail('part_failed', '录制上传失败', error))
      await this.ensureAbort()
      throw failure
    } finally {
      this.detachListeners()
    }
  }

  private fail(
    reason: UploadFailureReason,
    message: string,
    cause?: unknown,
  ): RecordingUploadError {
    if (this.failure) {
      return this.failure
    }
    if (this.state === 'completed') {
      // Events arriving after a successful complete (for example a late track
      // `ended`) must not flip a finished stream to failed.
      return new RecordingUploadError(
        reason,
        message,
        cause === undefined ? undefined : { cause },
      )
    }
    const failure = new RecordingUploadError(
      reason,
      message,
      cause === undefined ? undefined : { cause },
    )
    this.failure = failure
    this.state = 'failed'
    this.emitProgress('failed')
    for (const listener of this.errorListeners) {
      listener(failure)
    }
    void this.ensureAbort()
    return failure
  }

  private ensureAbort(): Promise<void> {
    if (!this.abortPromise) {
      this.abortPromise = (async () => {
        try {
          await this.api.abortMultipart(this.sessionId, this.streamType)
        } catch {
          // The stream is already failed; a failed abort must not mask that.
        }
      })()
    }
    return this.abortPromise
  }

  private emitProgress(status: UploadStatus): void {
    const uploadedBytes = this.sumPartSizes()
    const progress: UploadProgress = {
      uploadedBytes,
      totalBytes: Math.max(this.receivedBytes, uploadedBytes),
      status,
    }
    for (const listener of this.progressListeners) {
      listener({ ...progress })
    }
  }

  private sumPartSizes(): number {
    let total = 0
    for (const size of this.partSizes.values()) {
      total += size
    }
    return total
  }

  private get completeMime(): string {
    const declared = this.recorder.mimeType?.trim()
    return declared ? declared : STREAM_MIME[this.streamType]
  }

  private get baseMime(): string {
    const mediaType = this.completeMime.split(';', 1)[0]?.trim()
    return mediaType || 'application/octet-stream'
  }
}