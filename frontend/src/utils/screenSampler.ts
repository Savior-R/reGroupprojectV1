import { uploadScreen } from '../api/perception'

/**
 * Periodic screen JPEG sampler for OCR (05A §6.2, 02 §4.3).
 *
 * Uses the already-acquired screen MediaStream, draws a bounded canvas (960 px
 * wide by default), computes a cheap change score against the previous frame,
 * and posts one multipart request at a time. OCR failure never stops recording
 * or the sampler.
 */

export interface ScreenSamplerOptions {
  sessionId: string
  stream: MediaStream
  /** Current session-relative time in ms (the classroom tick's elapsed_ms). */
  atMs: () => number
  isActive: () => boolean
  intervalMs?: number
  maxWidth?: number
  jpegQuality?: number
  onError?: (error: Error) => void
  /** Test seams. */
  upload?: typeof uploadScreen
  createVideo?: () => HTMLVideoElement
  createCanvas?: () => HTMLCanvasElement
  setInterval?: (handler: () => void, delayMs: number) => number
  clearInterval?: (handle: number) => void
}

const DEFAULT_INTERVAL_MS = 5_000
const DEFAULT_MAX_WIDTH = 960
const DIFF_WIDTH = 160
const DIFF_THRESHOLD = 18

export class ScreenSampler {
  private readonly options: ScreenSamplerOptions
  private video: HTMLVideoElement | null = null
  private canvas: HTMLCanvasElement | null = null
  private diffCanvas: HTMLCanvasElement | null = null
  private timer: number | null = null
  private previous: Uint8ClampedArray | null = null
  private inFlight = false
  private stopped = false

  constructor(options: ScreenSamplerOptions) {
    this.options = options
  }

  async start(): Promise<void> {
    const video = (this.options.createVideo ?? (() => document.createElement('video')))()
    video.muted = true
    video.playsInline = true
    video.srcObject = this.options.stream
    this.video = video
    try {
      await video.play()
    } catch (error) {
      this.options.onError?.(
        error instanceof Error ? error : new Error('屏幕采样启动失败'),
      )
      return
    }
    this.canvas = (this.options.createCanvas ?? (() => document.createElement('canvas')))()
    this.diffCanvas = (this.options.createCanvas ?? (() => document.createElement('canvas')))()
    const schedule = this.options.setInterval ?? ((handler, delay) => window.setInterval(handler, delay))
    this.timer = schedule(() => {
      void this.capture()
    }, this.options.intervalMs ?? DEFAULT_INTERVAL_MS)
    await this.capture()
  }

  stop(): void {
    this.stopped = true
    if (this.timer !== null) {
      const clear = this.options.clearInterval ?? ((handle) => window.clearInterval(handle))
      clear(this.timer)
      this.timer = null
    }
    if (this.video) {
      this.video.pause()
      this.video.srcObject = null
      this.video = null
    }
    this.canvas = null
    this.diffCanvas = null
    this.previous = null
  }

  private async capture(): Promise<void> {
    if (this.stopped || this.inFlight || !this.options.isActive()) {
      return
    }
    const video = this.video
    const canvas = this.canvas
    const diffCanvas = this.diffCanvas
    if (!video || !canvas || !diffCanvas || video.videoWidth === 0) {
      return
    }
    this.inFlight = true
    try {
      const maxWidth = this.options.maxWidth ?? DEFAULT_MAX_WIDTH
      const scale = Math.min(1, maxWidth / video.videoWidth)
      const width = Math.max(1, Math.round(video.videoWidth * scale))
      const height = Math.max(1, Math.round(video.videoHeight * scale))
      canvas.width = width
      canvas.height = height
      const context = canvas.getContext('2d')
      if (!context) {
        return
      }
      context.drawImage(video, 0, 0, width, height)
      const changeScore = this.changeScore(diffCanvas, video)

      const jpeg = await toBlob(canvas, this.options.jpegQuality ?? 0.6)
      if (!jpeg) {
        return
      }
      const upload = this.options.upload ?? uploadScreen
      await upload(this.options.sessionId, jpeg, this.options.atMs(), changeScore)
    } catch (error) {
      this.options.onError?.(
        error instanceof Error ? error : new Error('屏幕截图上传失败'),
      )
    } finally {
      this.inFlight = false
    }
  }

  private changeScore(canvas: HTMLCanvasElement, video: HTMLVideoElement): number {
    const width = DIFF_WIDTH
    const height = Math.max(1, Math.round((width * video.videoHeight) / video.videoWidth))
    canvas.width = width
    canvas.height = height
    const context = canvas.getContext('2d', { willReadFrequently: true })
    if (!context) {
      return 1
    }
    context.drawImage(video, 0, 0, width, height)
    const current = context.getImageData(0, 0, width, height).data
    const previous = this.previous
    this.previous = current
    if (!previous || previous.length !== current.length) {
      return 1
    }
    let changed = 0
    let sampled = 0
    for (let i = 0; i < current.length; i += 4 * 4) {
      const delta =
        Math.abs(current[i]! - previous[i]!) +
        Math.abs(current[i + 1]! - previous[i + 1]!) +
        Math.abs(current[i + 2]! - previous[i + 2]!)
      if (delta / 3 >= DIFF_THRESHOLD) {
        changed += 1
      }
      sampled += 1
    }
    return sampled === 0 ? 0 : Math.min(1, changed / sampled)
  }
}

function toBlob(canvas: HTMLCanvasElement, quality: number): Promise<Blob | null> {
  return new Promise((resolve) => {
    canvas.toBlob((blob) => resolve(blob), 'image/jpeg', quality)
  })
}
