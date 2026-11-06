import type { WsSocket } from './wsClient'

/**
 * Perception WebSocket client (05A §6.2, 02 §6.1).
 *
 * Protocol v1 is raw (no event envelope): one `audio.start` text frame,
 * binary PCM16 mono 16 kHz frames, one `audio.stop` text frame. The server
 * answers with raw `asr.partial` / `asr.final` / `error` JSON frames.
 *
 * Perception failures degrade ASR only; the recording and control channel are
 * independent. A dropped socket is retried with a fresh one-time ticket while
 * the room is still active.
 */

export interface AsrPartial {
  type: 'asr.partial'
  text: string
  at_ms: number
}

export interface AsrFinal {
  type: 'asr.final'
  text: string
  text_id: string
  at_ms: number
}

export interface PerceptionError {
  type: 'error'
  code: string
  message: string
}

export type PerceptionMessage = AsrPartial | AsrFinal | PerceptionError

export type PerceptionStatus = 'connecting' | 'connected' | 'reconnecting' | 'closed' | 'failed'

export interface PerceptionClientOptions {
  sessionId: string
  ticketProvider: () => Promise<string>
  onMessage: (message: PerceptionMessage) => void
  onStatus?: (status: PerceptionStatus) => void
  /** Test seams. */
  socketFactory?: (url: string) => WsSocket
  urlBuilder?: (sessionId: string, ticket: string) => string
  setTimeout?: (handler: () => void, delayMs: number) => unknown
  maxReconnects?: number
}

const MAX_RECONNECTS = 3
const RECONNECT_DELAY_MS = 1_000
const WS_OPEN = 1

export class PerceptionClient {
  private readonly options: PerceptionClientOptions
  private socket: WsSocket | null = null
  private active = false
  private reconnects = 0

  constructor(options: PerceptionClientOptions) {
    this.options = options
  }

  async connect(): Promise<void> {
    if (this.active) {
      return
    }
    this.active = true
    this.reconnects = 0
    await this.open()
  }

  /** Sends one PCM16 mono 16 kHz chunk; returns false when the socket is not open. */
  sendAudio(chunk: ArrayBuffer): boolean {
    if (!this.socket || this.socket.readyState !== WS_OPEN) {
      return false
    }
    this.socket.send(chunk)
    return true
  }

  /** Sends audio.stop and closes after the server drains (bounded). */
  async stop(): Promise<void> {
    if (!this.active) {
      this.close()
      return
    }
    this.active = false
    const socket = this.socket
    if (!socket || socket.readyState !== WS_OPEN) {
      this.close()
      return
    }
    try {
      socket.send(JSON.stringify({ type: 'audio.stop' }))
    } catch {
      // Closing anyway.
    }
    await new Promise<void>((resolve) => {
      const timer = window.setTimeout(() => resolve(), 2_000)
      const previous = socket.onclose
      socket.onclose = (event) => {
        window.clearTimeout(timer)
        previous?.(event)
        resolve()
      }
    })
    this.close()
  }

  close(): void {
    this.active = false
    const socket = this.socket
    this.socket = null
    if (socket) {
      socket.onopen = null
      socket.onmessage = null
      socket.onclose = null
      socket.onerror = null
      try {
        socket.close()
      } catch {
        // Already closed.
      }
    }
    this.options.onStatus?.('closed')
  }

  private async open(): Promise<void> {
    this.options.onStatus?.(this.reconnects > 0 ? 'reconnecting' : 'connecting')
    let ticket: string
    try {
      ticket = await this.options.ticketProvider()
    } catch {
      this.fail()
      return
    }
    if (!this.active) {
      return
    }
    const url = (this.options.urlBuilder ?? defaultUrl)(this.options.sessionId, ticket)
    const socket = (this.options.socketFactory ?? defaultSocketFactory)(url)
    this.socket = socket
    socket.onopen = () => {
      if (!this.active || this.socket !== socket) {
        return
      }
      this.reconnects = 0
      socket.send(JSON.stringify({
        type: 'audio.start',
        format: 'pcm16',
        sample_rate: 16_000,
        channels: 1,
      }))
      this.options.onStatus?.('connected')
    }
    socket.onmessage = (event) => {
      const message = parseMessage(event.data)
      if (message) {
        this.options.onMessage(message)
        if (message.type === 'error' && message.code === 'VALIDATION_ERROR') {
          this.fail()
        }
      }
    }
    socket.onerror = () => {
      // onclose follows; reconnect there.
    }
    socket.onclose = () => {
      if (this.socket !== socket) {
        return
      }
      this.socket = null
      if (!this.active) {
        this.options.onStatus?.('closed')
        return
      }
      this.scheduleReconnect()
    }
  }

  private scheduleReconnect(): void {
    const max = this.options.maxReconnects ?? MAX_RECONNECTS
    if (this.reconnects >= max) {
      this.fail()
      return
    }
    this.reconnects += 1
    const timer = this.options.setTimeout ?? ((handler, delay) => window.setTimeout(handler, delay))
    timer(() => {
      if (this.active) {
        void this.open()
      }
    }, RECONNECT_DELAY_MS)
  }

  private fail(): void {
    this.active = false
    this.options.onStatus?.('failed')
    this.close()
  }
}

function defaultSocketFactory(url: string): WsSocket {
  return new WebSocket(url) as unknown as WsSocket
}

function defaultUrl(sessionId: string, ticket: string): string {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}/ws/v1/sessions/${encodeURIComponent(sessionId)}/perception?ticket=${encodeURIComponent(ticket)}`
}

function parseMessage(data: unknown): PerceptionMessage | null {
  if (typeof data !== 'string') {
    return null
  }
  let parsed: unknown
  try {
    parsed = JSON.parse(data)
  } catch {
    return null
  }
  if (typeof parsed !== 'object' || parsed === null) {
    return null
  }
  const value = parsed as Record<string, unknown>
  if (value.type === 'asr.partial' && typeof value.text === 'string') {
    return {
      type: 'asr.partial',
      text: value.text,
      at_ms: typeof value.at_ms === 'number' ? value.at_ms : 0,
    }
  }
  if (value.type === 'asr.final' && typeof value.text === 'string') {
    return {
      type: 'asr.final',
      text: value.text,
      text_id: typeof value.text_id === 'string' ? value.text_id : '',
      at_ms: typeof value.at_ms === 'number' ? value.at_ms : 0,
    }
  }
  if (value.type === 'error') {
    return {
      type: 'error',
      code: typeof value.code === 'string' ? value.code : 'ERROR',
      message: typeof value.message === 'string' ? value.message : '感知服务错误',
    }
  }
  return null
}
