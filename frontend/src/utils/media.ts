import type { StreamType } from '../types/recording'

/**
 * Browser media acquisition for the recording rooms (05B §3, 09 §6.5).
 *
 * Chrome/Edge only: `MediaRecorder` with VP8/Opus WebM is the backend's
 * supported format. The module is framework-free and all browser globals are
 * resolved lazily so Vitest can inject fakes without touching a real camera.
 */

export type MediaMode = 'free' | 'scenario' | 'interview'
export type MediaErrorReason = 'permission' | 'unsupported' | 'missing'

/** Stream MIME types required by the backend (05B §3). */
export const STREAM_MIME: Record<StreamType, string> = {
  camera: 'video/webm;codecs=vp8',
  screen: 'video/webm;codecs=vp8',
  audio: 'audio/webm;codecs=opus',
}

export const DEFAULT_TIMESLICE_MS = 5_000

export class MediaError extends Error {
  readonly reason: MediaErrorReason

  constructor(
    reason: MediaErrorReason,
    message: string,
    options?: { cause?: unknown },
  ) {
    super(message, options)
    this.name = 'MediaError'
    this.reason = reason
  }
}

export interface MediaDevicesLike {
  getUserMedia(constraints: MediaStreamConstraints): Promise<MediaStream>
  getDisplayMedia?(constraints: DisplayMediaStreamOptions): Promise<MediaStream>
}

export interface AcquireMediaDeps {
  /** Test seam: defaults to `navigator.mediaDevices`. */
  mediaDevices?: MediaDevicesLike
  /** Test seam: defaults to `navigator.userAgent`. */
  userAgent?: string
  /** Test seam: defaults to `new MediaStream(tracks)`. */
  createStream?: (tracks: MediaStreamTrack[]) => MediaStream
}

export interface AcquiredMedia {
  camera: MediaStream
  screen?: MediaStream
  audio: MediaStream
  stop(): void
}

/** Chrome and Edge both expose the VP8/Opus WebM encoders the backend needs. */
export function isSupportedBrowser(userAgent: string): boolean {
  return /(?:Edg|Chrome|Chromium)\//.test(userAgent) && !/OPR\//.test(userAgent)
}

function defaultUserAgent(): string {
  return typeof navigator === 'undefined' ? '' : navigator.userAgent
}

function defaultMediaDevices(): MediaDevicesLike {
  const devices = typeof navigator === 'undefined' ? undefined : navigator.mediaDevices
  if (!devices) {
    throw new MediaError('unsupported', '当前浏览器不支持媒体采集')
  }
  return devices
}

function defaultCreateStream(tracks: MediaStreamTrack[]): MediaStream {
  return new MediaStream(tracks)
}

function errorName(error: unknown): string {
  if (typeof error !== 'object' || error === null || !('name' in error)) {
    return ''
  }
  const name = (error as { name?: unknown }).name
  return typeof name === 'string' ? name : ''
}

function toMediaError(error: unknown, fallbackMessage: string): MediaError {
  if (error instanceof MediaError) {
    return error
  }
  const message = error instanceof Error && error.message ? error.message : fallbackMessage
  switch (errorName(error)) {
    case 'NotAllowedError':
    case 'PermissionDeniedError':
    case 'SecurityError':
      return new MediaError('permission', message, { cause: error })
    case 'NotFoundError':
    case 'DevicesNotFoundError':
    case 'OverconstrainedError':
    case 'ConstraintNotSatisfiedError':
    case 'NotReadableError':
    case 'TrackStartError':
    case 'AbortError':
      return new MediaError('missing', message, { cause: error })
    case 'NotSupportedError':
      return new MediaError('unsupported', message, { cause: error })
    default:
      return new MediaError('missing', message, { cause: error })
  }
}

/**
 * Acquires the streams required by a session mode.
 *
 * Free/scenario sessions record camera + screen + microphone; interviews record
 * camera + microphone only. `stop()` stops every acquired track exactly once.
 */
export async function acquireMedia(
  mode: MediaMode,
  deps: AcquireMediaDeps = {},
): Promise<AcquiredMedia> {
  const userAgent = deps.userAgent ?? defaultUserAgent()
  if (!isSupportedBrowser(userAgent)) {
    throw new MediaError('unsupported', '当前浏览器不支持录制，请使用 Chrome 或 Edge')
  }

  const mediaDevices = deps.mediaDevices ?? defaultMediaDevices()
  const createStream = deps.createStream ?? defaultCreateStream
  const tracks: MediaStreamTrack[] = []

  const remember = (stream: MediaStream): void => {
    for (const track of stream.getTracks()) {
      if (!tracks.includes(track)) {
        tracks.push(track)
      }
    }
  }

  const stopTracks = (): void => {
    for (const track of tracks) {
      try {
        track.stop()
      } catch {
        // A track that is already gone must not prevent the others stopping.
      }
    }
  }

  try {
    const combined = await mediaDevices.getUserMedia({ video: true, audio: true })
    remember(combined)

    const videoTracks = combined.getVideoTracks()
    const audioTracks = combined.getAudioTracks()
    if (videoTracks.length === 0 || audioTracks.length === 0) {
      throw new MediaError('missing', '未检测到可用的摄像头或麦克风')
    }

    const camera = createStream(videoTracks)
    const audio = createStream(audioTracks)

    let screen: MediaStream | undefined
    if (mode !== 'interview') {
      if (typeof mediaDevices.getDisplayMedia !== 'function') {
        throw new MediaError('unsupported', '当前浏览器不支持屏幕共享')
      }
      screen = await mediaDevices.getDisplayMedia({ video: true })
      remember(screen)
    }

    let stopped = false
    return {
      camera,
      screen,
      audio,
      stop(): void {
        if (stopped) {
          return
        }
        stopped = true
        stopTracks()
      },
    }
  } catch (error) {
    // Never leave the camera/microphone light on after a partial acquisition.
    stopTracks()
    throw toMediaError(error, '媒体设备不可用')
  }
}

/**
 * Creates and starts a `MediaRecorder` with the 5 s timeslice required by 05B.
 * Throws `MediaError` when the browser or codec is unsupported.
 */
export function createRecorder(
  stream: MediaStream,
  mimeType: string,
  timesliceMs = DEFAULT_TIMESLICE_MS,
): MediaRecorder {
  if (typeof MediaRecorder === 'undefined') {
    throw new MediaError('unsupported', '当前浏览器不支持 MediaRecorder')
  }
  if (
    typeof MediaRecorder.isTypeSupported === 'function' &&
    !MediaRecorder.isTypeSupported(mimeType)
  ) {
    throw new MediaError('unsupported', `不支持的录制编码: ${mimeType}`)
  }

  let recorder: MediaRecorder
  try {
    recorder = new MediaRecorder(stream, { mimeType })
  } catch (error) {
    throw toMediaError(error, '无法创建录制器')
  }
  recorder.start(timesliceMs)
  return recorder
}