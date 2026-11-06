/**
 * Microphone -> PCM16 mono 16 kHz capture for the perception WebSocket (05A §6.2).
 *
 * The recording MediaStream is reused; MediaRecorder and the AudioWorklet tap
 * are independent consumers. AudioWorklet keeps capture off the main thread;
 * the main thread converts each 100 ms block to little-endian Int16 and hands
 * it to the perception client. If the AudioContext cannot run at 16 kHz the
 * block is downsampled by averaging before conversion.
 */

const TARGET_RATE = 16_000
const BLOCK_MS = 100

const WORKLET_SOURCE = `
class M3PcmBlockProcessor extends AudioWorkletProcessor {
  constructor(options) {
    super()
    this.blockSize = options.processorOptions.blockSize
    this.buffer = new Float32Array(this.blockSize)
    this.offset = 0
  }
  process(inputs) {
    const channel = inputs[0] && inputs[0][0]
    if (!channel) return true
    for (let i = 0; i < channel.length; i += 1) {
      this.buffer[this.offset] = channel[i]
      this.offset += 1
      if (this.offset === this.blockSize) {
        this.port.postMessage(this.buffer.slice(0))
        this.offset = 0
      }
    }
    return true
  }
}
registerProcessor('m3-pcm-block', M3PcmBlockProcessor)
`

export interface PcmCaptureOptions {
  /** Called with little-endian PCM16 mono 16 kHz bytes. */
  onChunk: (chunk: ArrayBuffer) => void
  onError?: (error: Error) => void
  /** Test seam: defaults to the browser AudioContext. */
  audioContextFactory?: (options: AudioContextOptions) => AudioContext
}

export class PcmCapture {
  private readonly options: PcmCaptureOptions
  private context: AudioContext | null = null
  private source: MediaStreamAudioSourceNode | null = null
  private node: AudioWorkletNode | null = null
  private sink: GainNode | null = null
  private workletUrl: string | null = null

  constructor(options: PcmCaptureOptions) {
    this.options = options
  }

  async start(stream: MediaStream): Promise<void> {
    if (this.context) {
      return
    }
    const factory =
      this.options.audioContextFactory ?? ((options) => new AudioContext(options))
    let context: AudioContext
    try {
      context = factory({ sampleRate: TARGET_RATE })
    } catch (error) {
      this.options.onError?.(
        error instanceof Error ? error : new Error('麦克风采样启动失败'),
      )
      return
    }
    this.context = context
    try {
      this.workletUrl = URL.createObjectURL(
        new Blob([WORKLET_SOURCE], { type: 'application/javascript' }),
      )
      await context.audioWorklet.addModule(this.workletUrl)
      this.source = context.createMediaStreamSource(stream)
      this.node = new AudioWorkletNode(context, 'm3-pcm-block', {
        processorOptions: {
          blockSize: Math.max(1, Math.round(context.sampleRate * (BLOCK_MS / 1000))),
        },
      })
      // A zero-gain sink keeps the graph pulling without echoing the mic.
      this.sink = context.createGain()
      this.sink.gain.value = 0
      this.source.connect(this.node)
      this.node.connect(this.sink)
      this.sink.connect(context.destination)
      this.node.port.onmessage = (event: MessageEvent<Float32Array>) => {
        const pcm = toPcm16(event.data, context.sampleRate)
        if (pcm.byteLength > 0) {
          this.options.onChunk(pcm)
        }
      }
      await context.resume()
    } catch (error) {
      this.stop()
      this.options.onError?.(
        error instanceof Error ? error : new Error('麦克风采样启动失败'),
      )
    }
  }

  stop(): void {
    this.node?.port.close()
    this.node?.disconnect()
    this.source?.disconnect()
    this.sink?.disconnect()
    this.node = null
    this.source = null
    this.sink = null
    if (this.workletUrl) {
      URL.revokeObjectURL(this.workletUrl)
      this.workletUrl = null
    }
    if (this.context) {
      void this.context.close().catch(() => undefined)
      this.context = null
    }
  }
}

/** Converts a float block to PCM16 mono 16 kHz (averaging when downsampling). */
export function toPcm16(input: Float32Array, inputRate: number): ArrayBuffer {
  if (input.length === 0) {
    return new ArrayBuffer(0)
  }
  const ratio = inputRate / TARGET_RATE
  const outputLength = ratio <= 1 ? input.length : Math.floor(input.length / ratio)
  const output = new ArrayBuffer(outputLength * 2)
  const view = new DataView(output)
  for (let i = 0; i < outputLength; i += 1) {
    let sample: number
    if (ratio <= 1) {
      sample = input[i] ?? 0
    } else {
      const start = Math.floor(i * ratio)
      const end = Math.min(input.length, Math.floor((i + 1) * ratio))
      let sum = 0
      for (let j = start; j < end; j += 1) {
        sum += input[j] ?? 0
      }
      sample = end > start ? sum / (end - start) : 0
    }
    const clamped = Math.max(-1, Math.min(1, sample))
    const scaled = clamped < 0 ? clamped * 32_768 : clamped * 32_767
    view.setInt16(i * 2, Math.max(-32_768, Math.min(32_767, Math.round(scaled))), true)
  }
  return output
}
