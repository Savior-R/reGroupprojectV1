import { describe, expect, it, vi, type Mock } from 'vitest'
import type {
  MultipartStart,
  PartUrl,
  RecordingStreamView,
  StreamType,
} from '../types/recording'
import {
  MAX_BUFFERED_BYTES,
  MAX_PART_ATTEMPTS,
  RecorderUploader,
  RecordingUploadError,
  type FixWebmDuration,
  type RecorderLike,
  type RecorderUploaderApi,
  type TrackLike,
  type UploadFetch,
  type UploadResponseLike,
} from './recorderUploader'

const MB = 1024 * 1024
const PART_SIZE = 5 * MB

function blobOf(size: number, fill = 0): Blob {
  return new Blob([new Uint8Array(size).fill(fill)])
}

function headerMap(values: Record<string, string>) {
  const lower = new Map(
    Object.entries(values).map(([key, value]) => [key.toLowerCase(), value] as const),
  )
  return {
    get(name: string): string | null {
      return lower.get(name.toLowerCase()) ?? null
    },
  }
}

interface FetchHarness {
  fetch: UploadFetch
  calls: Array<{ url: string; method: string; body: Blob }>
  urls: string[]
}

function createFetchHarness(
  options: { fail?: boolean; onPut?: (attempt: number) => void } = {},
): FetchHarness {
  const calls: FetchHarness['calls'] = []
  const urls: string[] = []
  let attempt = 0
  const fetchFn = vi.fn(async (url: string, init: RequestInit): Promise<UploadResponseLike> => {
    attempt += 1
    calls.push({ url, method: String(init.method), body: init.body as Blob })
    urls.push(url)
    options.onPut?.(attempt)
    if (options.fail) {
      return { ok: false, status: 500, headers: headerMap({}) }
    }
    return { ok: true, status: 200, headers: headerMap({ ETag: `"etag-${attempt}"` }) }
  })
  return { fetch: fetchFn as unknown as UploadFetch, calls, urls }
}

class FakeTrack implements TrackLike {
  readyState = 'live'
  private readonly listeners = new Set<() => void>()
  readonly stop = vi.fn(() => {
    this.readyState = 'ended'
  })

  addEventListener(type: 'ended', listener: () => void): void {
    if (type === 'ended') {
      this.listeners.add(listener)
    }
  }

  end(): void {
    this.readyState = 'ended'
    for (const listener of this.listeners) {
      listener()
    }
  }
}

class FakeRecorder implements RecorderLike {
  mimeType = 'video/webm;codecs=vp8'
  state: 'inactive' | 'recording' = 'recording'
  readonly stream: { getTracks(): TrackLike[] }
  private readonly listeners = new Map<string, Set<(event: any) => void>>()

  constructor(tracks: TrackLike[] = []) {
    this.stream = { getTracks: () => tracks }
  }

  addEventListener(type: string, listener: (event: any) => void): void {
    let bucket = this.listeners.get(type)
    if (!bucket) {
      bucket = new Set()
      this.listeners.set(type, bucket)
    }
    bucket.add(listener)
  }

  removeEventListener(type: string, listener: (event: any) => void): void {
    this.listeners.get(type)?.delete(listener)
  }

  private emit(type: string, event: any = {}): void {
    for (const listener of this.listeners.get(type) ?? []) {
      listener(event)
    }
  }

  emitData(blob: Blob): void {
    this.emit('dataavailable', { data: blob })
  }

  stop(): void {
    if (this.state === 'inactive') {
      return
    }
    this.state = 'inactive'
    this.emit('stop')
  }
}

const streamView: RecordingStreamView = {
  stream_type: 'camera',
  status: 'uploaded',
  upload_id: 'upload-1',
  object_key: 'recordings/school/task/session/camera.webm',
  parts_uploaded: 1,
  bytes_uploaded: PART_SIZE,
  duration_ms: 1000,
  offset_ms: 0,
  mime: 'video/webm;codecs=vp8',
  part_size: PART_SIZE,
}

interface ApiHarness {
  api: RecorderUploaderApi
  startMultipart: ReturnType<typeof vi.fn>
  partUrl: ReturnType<typeof vi.fn>
  completeMultipart: ReturnType<typeof vi.fn>
  abortMultipart: ReturnType<typeof vi.fn>
}

function createApiHarness(): ApiHarness {
  const startMultipart = vi.fn(
    async (_sessionId: string, _streamType: StreamType): Promise<MultipartStart> => ({
      upload_id: 'upload-1',
      object_key: 'recordings/school/task/session/camera.webm',
      part_size: PART_SIZE,
    }),
  )
  const partUrl = vi.fn(
    async (
      _sessionId: string,
      _streamType: StreamType,
      partNumber: number,
    ): Promise<PartUrl> => ({
      url: `https://minio.test/part/${partNumber}`,
      part_number: partNumber,
      expires_in_seconds: 900,
    }),
  )
  const completeMultipart = vi.fn(
    async (
      _sessionId: string,
      _streamType: StreamType,
      _body: unknown,
    ): Promise<RecordingStreamView> => streamView,
  )
  const abortMultipart = vi.fn(async (_sessionId: string, _streamType: StreamType) => {})
  return {
    api: { startMultipart, partUrl, completeMultipart, abortMultipart },
    startMultipart,
    partUrl,
    completeMultipart,
    abortMultipart,
  }
}

interface Harness {
  uploader: RecorderUploader
  recorder: FakeRecorder
  api: ApiHarness
  fetchHarness: FetchHarness
  fixWebmDuration: Mock<FixWebmDuration>
  progress: Array<{ uploadedBytes: number; totalBytes: number; status: string }>
  errors: RecordingUploadError[]
  setNow(value: number): void
}

interface SetupOptions {
  fetchFail?: boolean
  fixFails?: boolean
  track?: FakeTrack
  /** Simulates a slow PUT consuming wall-clock time. */
  advanceClockOnPut?: number
  /** Simulates a slow duration patch consuming wall-clock time. */
  advanceClockOnFix?: number
  onPut?: (attempt: number) => void
}

function setup(options: SetupOptions = {}): Harness {
  let now = 0
  const recorder = new FakeRecorder(options.track ? [options.track] : [])
  const api = createApiHarness()
  const fetchHarness = createFetchHarness({
    fail: options.fetchFail,
    onPut: (attempt) => {
      if (options.advanceClockOnPut) {
        now += options.advanceClockOnPut
      }
      options.onPut?.(attempt)
    },
  })
  const fixWebmDuration = vi.fn<FixWebmDuration>(async (blob, _durationMs) => {
    if (options.advanceClockOnFix) {
      now += options.advanceClockOnFix
    }
    return blob
  })
  if (options.fixFails) {
    fixWebmDuration.mockRejectedValue(new Error('patch failed'))
  }
  const progress: Harness['progress'] = []
  const errors: RecordingUploadError[] = []

  const uploader = new RecorderUploader(api.api, 'session-1', 'camera', recorder, {
    fetch: fetchHarness.fetch,
    now: () => now,
    fixWebmDuration,
  })
  uploader.onProgress((value) => progress.push(value))
  uploader.onError((error) => errors.push(error))

  return {
    uploader,
    recorder,
    api,
    fetchHarness,
    fixWebmDuration,
    progress,
    errors,
    setNow(value: number) {
      now = value
    },
  }
}

describe('RecorderUploader', () => {
  it('uploads 5MB parts and completes with all etags', async () => {
    // Slow PUTs and a slow duration patch after stop must not inflate the
    // recording duration written to part 1 or reported to the server.
    const harness = setup({ advanceClockOnPut: 1_000, advanceClockOnFix: 5_000 })
    await harness.uploader.start()

    harness.recorder.emitData(blobOf(PART_SIZE, 1))
    harness.recorder.emitData(blobOf(PART_SIZE, 2))
    harness.recorder.emitData(blobOf(1.5 * MB, 3))
    harness.setNow(12_345)

    const view = await harness.uploader.stop()

    expect(view).toBe(streamView)
    expect(harness.api.startMultipart).toHaveBeenCalledTimes(1)
    expect(harness.api.partUrl.mock.calls.map((call) => call[2])).toEqual([1, 2, 1, 3])
    expect(harness.fetchHarness.calls.map((call) => call.method)).toEqual([
      'PUT',
      'PUT',
      'PUT',
      'PUT',
    ])
    expect(harness.fetchHarness.calls.map((call) => call.body.size)).toEqual([
      PART_SIZE,
      PART_SIZE,
      PART_SIZE,
      1.5 * MB,
    ])

    // The first part is sent twice; the latest etag must win.
    const completeBody = harness.api.completeMultipart.mock.calls[0]![2] as {
      parts: Array<{ part_number: number; etag: string }>
      size: number
      duration_ms: number
      mime: string
    }
    expect(completeBody.parts).toEqual([
      { part_number: 1, etag: '"etag-3"' },
      { part_number: 2, etag: '"etag-2"' },
      { part_number: 3, etag: '"etag-4"' },
    ])
    expect(completeBody.size).toBe(5 * MB + 5 * MB + 1.5 * MB)
    expect(completeBody.duration_ms).toBe(12_345)
    expect(completeBody.mime).toBe('video/webm;codecs=vp8')

    // The duration patch sees the stop-time duration even though the PUTs and
    // the patch itself advanced the fake clock afterwards.
    expect(harness.fixWebmDuration).toHaveBeenCalledTimes(1)
    expect(harness.fixWebmDuration.mock.calls[0]![1]).toBe(12_345)

    expect(harness.progress.at(-1)).toEqual({
      uploadedBytes: 11.5 * MB,
      totalBytes: 11.5 * MB,
      status: 'completed',
    })
  })

  it('buffers dataavailable emitted before start() and uploads it', async () => {
    const harness = setup()
    const early = blobOf(1 * MB, 7)
    harness.recorder.emitData(early)

    expect(harness.api.startMultipart).not.toHaveBeenCalled()
    harness.setNow(2_000)
    await harness.uploader.start()
    harness.setNow(3_000)

    await harness.uploader.stop()

    expect(harness.fetchHarness.calls).toHaveLength(1)
    expect(harness.fetchHarness.calls[0]!.body.size).toBe(1 * MB)
    const uploaded = new Uint8Array(
      await harness.fetchHarness.calls[0]!.body.arrayBuffer(),
    )
    expect(uploaded[0]).toBe(7)

    const completeBody = harness.api.completeMultipart.mock.calls[0]![2] as {
      duration_ms: number
    }
    expect(completeBody.duration_ms).toBe(3_000)
  })

  it('flushes pre-start data as a full part once multipart begins', async () => {
    const harness = setup()
    harness.recorder.emitData(blobOf(PART_SIZE, 3))
    await harness.uploader.start()
    harness.recorder.emitData(blobOf(1 * MB, 4))
    harness.setNow(1_000)

    await harness.uploader.stop()

    expect(harness.api.partUrl.mock.calls.map((call) => call[2])).toEqual([1, 1, 2])
    expect(harness.fetchHarness.calls.map((call) => call.body.size)).toEqual([
      PART_SIZE,
      PART_SIZE,
      1 * MB,
    ])
  })

  it('rejects stop() before start() and aborts exactly once', async () => {
    const harness = setup()

    await expect(harness.uploader.stop()).rejects.toMatchObject({
      name: 'RecordingUploadError',
      reason: 'start_failed',
    })

    expect(harness.api.startMultipart).not.toHaveBeenCalled()
    expect(harness.api.abortMultipart).toHaveBeenCalledTimes(1)
  })

  it('attaches recorder listeners once when start() is called twice', async () => {
    const harness = setup()
    await harness.uploader.start()
    await harness.uploader.start()

    harness.recorder.emitData(blobOf(1 * MB))
    harness.setNow(1_000)
    await harness.uploader.stop()

    expect(harness.fetchHarness.calls).toHaveLength(1)
    expect(harness.fetchHarness.calls[0]!.body.size).toBe(1 * MB)
  })

  it('retains part 1 and re-uploads it after the duration patch', async () => {
    const harness = setup()
    harness.fixWebmDuration.mockImplementation(async (blob: Blob, _durationMs: number) => {
      const text = `patched:${blob.size}`
      return new Blob([text])
    })
    await harness.uploader.start()

    harness.recorder.emitData(blobOf(PART_SIZE, 1))
    harness.recorder.emitData(blobOf(PART_SIZE, 2))
    harness.setNow(5_000)

    await harness.uploader.stop()

    expect(harness.fixWebmDuration).toHaveBeenCalledTimes(1)
    const [firstPart, durationMs] = harness.fixWebmDuration.mock.calls[0]!
    expect((firstPart as Blob).size).toBe(PART_SIZE)
    expect(durationMs).toBe(5_000)

    const partOnePuts = harness.fetchHarness.calls.filter((_call, index) =>
      [0, 2].includes(index),
    )
    expect(partOnePuts).toHaveLength(2)
    expect(partOnePuts[1]!.body.size).toBe('patched:5242880'.length)

    const completeBody = harness.api.completeMultipart.mock.calls[0]![2] as {
      parts: Array<{ part_number: number; etag: string }>
      size: number
    }
    expect(completeBody.parts.map((part) => part.part_number)).toEqual([1, 2])
    expect(completeBody.parts[0]!.etag).toBe('"etag-3"')
    expect(completeBody.size).toBe(PART_SIZE + 'patched:5242880'.length)
  })

  it('patches and uploads a single part when the recording is under 5MB', async () => {
    const harness = setup()
    await harness.uploader.start()

    harness.recorder.emitData(blobOf(1 * MB))
    harness.setNow(2_500)

    await harness.uploader.stop()

    expect(harness.api.partUrl.mock.calls.map((call) => call[2])).toEqual([1])
    expect(harness.fetchHarness.calls).toHaveLength(1)
    expect(harness.fetchHarness.calls[0]!.body.size).toBe(1 * MB)
    expect(harness.fixWebmDuration).toHaveBeenCalledTimes(1)
    expect((harness.fixWebmDuration.mock.calls[0]![0] as Blob).size).toBe(1 * MB)
    expect(harness.fixWebmDuration.mock.calls[0]![1]).toBe(2_500)

    const completeBody = harness.api.completeMultipart.mock.calls[0]![2] as {
      parts: Array<{ part_number: number; etag: string }>
      size: number
      duration_ms: number
    }
    expect(completeBody.parts).toEqual([{ part_number: 1, etag: '"etag-1"' }])
    expect(completeBody.size).toBe(1 * MB)
    expect(completeBody.duration_ms).toBe(2_500)
  })

  it('ignores a late track-ended event after a successful complete', async () => {
    const track = new FakeTrack()
    const harness = setup({ track })
    await harness.uploader.start()

    harness.recorder.emitData(blobOf(1 * MB))
    harness.setNow(1_000)
    await harness.uploader.stop()

    const abortCalls = harness.api.abortMultipart.mock.calls.length
    track.end()

    expect(harness.errors).toHaveLength(0)
    expect(harness.api.abortMultipart).toHaveBeenCalledTimes(abortCalls)
  })

  it('does not complete when a track ends during the tail upload', async () => {
    const track = new FakeTrack()
    const harness = setup({
      track,
      onPut: (attempt) => {
        if (attempt === 3) {
          track.end()
        }
      },
    })
    await harness.uploader.start()

    harness.recorder.emitData(blobOf(PART_SIZE, 1))
    harness.recorder.emitData(blobOf(1 * MB, 2))
    harness.setNow(4_000)

    await expect(harness.uploader.stop()).rejects.toMatchObject({
      name: 'RecordingUploadError',
      reason: 'track_ended',
    })

    expect(harness.fetchHarness.calls).toHaveLength(3)
    expect(harness.api.completeMultipart).not.toHaveBeenCalled()
    expect(harness.api.abortMultipart).toHaveBeenCalledTimes(1)
    expect(harness.errors.at(-1)?.reason).toBe('track_ended')
  })

  it(`retries a failed part ${MAX_PART_ATTEMPTS} times before reporting and never completes`, async () => {
    const harness = setup({ fetchFail: true })
    await harness.uploader.start()

    harness.recorder.emitData(blobOf(PART_SIZE))

    await expect(harness.uploader.stop()).rejects.toMatchObject({
      name: 'RecordingUploadError',
      reason: 'part_failed',
    })

    expect(harness.fetchHarness.calls).toHaveLength(MAX_PART_ATTEMPTS)
    expect(harness.api.partUrl).toHaveBeenCalledTimes(MAX_PART_ATTEMPTS)
    expect(harness.api.completeMultipart).not.toHaveBeenCalled()
    expect(harness.api.abortMultipart).toHaveBeenCalledTimes(1)
    expect(harness.errors.at(-1)?.reason).toBe('part_failed')
  })

  it('aborts when the buffer exceeds 30MB of backlog', async () => {
    const harness = setup()
    await harness.uploader.start()

    harness.recorder.emitData(blobOf(MAX_BUFFERED_BYTES + 1))

    await expect(harness.uploader.stop()).rejects.toMatchObject({
      name: 'RecordingUploadError',
      reason: 'backlog',
    })

    expect(harness.fetchHarness.calls).toHaveLength(0)
    expect(harness.api.completeMultipart).not.toHaveBeenCalled()
    expect(harness.api.abortMultipart).toHaveBeenCalledTimes(1)

    // Aborting again must not issue a second API call.
    await harness.uploader.abort()
    expect(harness.api.abortMultipart).toHaveBeenCalledTimes(1)
  })

  it('aborts on track ended', async () => {
    const track = new FakeTrack()
    const harness = setup()
    const recorder = new FakeRecorder([track])
    const uploader = new RecorderUploader(
      harness.api.api,
      'session-1',
      'camera',
      recorder,
      {
        fetch: harness.fetchHarness.fetch,
        fixWebmDuration: harness.fixWebmDuration,
      },
    )
    const errors: RecordingUploadError[] = []
    uploader.onError((error) => errors.push(error))

    await uploader.start()
    track.end()

    await expect(uploader.stop()).rejects.toMatchObject({
      name: 'RecordingUploadError',
      reason: 'track_ended',
    })
    expect(harness.api.abortMultipart).toHaveBeenCalledTimes(1)
    expect(errors.at(-1)?.reason).toBe('track_ended')

    await uploader.abort()
    expect(harness.api.abortMultipart).toHaveBeenCalledTimes(1)
  })

  it('aborts and blocks completion when the duration patch fails', async () => {
    const harness = setup({ fixFails: true })
    await harness.uploader.start()

    harness.recorder.emitData(blobOf(PART_SIZE))

    await expect(harness.uploader.stop()).rejects.toMatchObject({
      name: 'RecordingUploadError',
      reason: 'duration_patch',
    })

    expect(harness.api.completeMultipart).not.toHaveBeenCalled()
    expect(harness.api.abortMultipart).toHaveBeenCalledTimes(1)
    expect(harness.errors.at(-1)?.reason).toBe('duration_patch')
  })
})