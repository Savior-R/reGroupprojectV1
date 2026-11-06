import type { ClientCommandType, EventPayload } from '../types/event'
import type { ClientCommand, ServerEvent } from '../types/session'

/**
 * Framework-free control-channel WebSocket client (02 §6, 05A §8).
 *
 * Responsibilities:
 * - fetch a one-time ticket and connect to `/ws/v1/sessions/{id}?ticket=...`;
 * - deduplicate server events by `seq` while still delivering control messages
 *   (`server.ack` / `error` / `ping`) that do not advance the sequence;
 * - answer `ping` with `pong`;
 * - reconnect within the 30 s grace window by requesting a fresh ticket and
 *   sending `session.resume {last_seq, recording_state}`;
 * - expose the reconnect countdown and terminal failure to the caller.
 *
 * The browser `WebSocket`, timers, clock and command-id generator are
 * injectable so the client can be unit-tested without a real socket.
 */

export const WS_GRACE_MS = 30_000
export const WS_RECONNECT_RETRY_MS = 1_000
const WS_OPEN = 1
const PROTOCOL_VERSION = 1

export type WsFailureReason = 'recording_lost' | 'reconnect_timeout' | 'connect_failed'

/**
 * Connection status emitted through `onStatus`.
 *
 * `reconnecting` carries the whole seconds left in the 30 s grace window, so
 * the UI can render a countdown without owning a timer.
 */
export type WsStatus =
  | { status: 'connecting' }
  | { status: 'connected' }
  | { status: 'reconnecting'; secondsLeft: number }
  | { status: 'failed'; reason: WsFailureReason }

export interface WsSocket {
  readyState: number
  send(data: string | ArrayBuffer): void
  close(code?: number, reason?: string): void
  onopen: (() => void) | null
  onmessage: ((event: { data: unknown }) => void) | null
  onclose: ((event: { code: number; reason: string }) => void) | null
  onerror: ((event: unknown) => void) | null
}

export type WsSocketFactory = (url: string) => WsSocket

export interface WsClientOptions {
  sessionId: string
  /** Fetches a fresh one-time ticket (`POST /sessions/{id}/ws-ticket`). */
  ticketProvider: () => Promise<string>
  onEvent: (event: ServerEvent) => void
  onStatus: (status: WsStatus) => void
  /** Whether the local recorder is still alive; `lost` forbids reconnect. */
  recordingState: () => 'active' | 'lost'
  /** Test seam: defaults to the browser `WebSocket`. */
  socketFactory?: WsSocketFactory
  /** Test seam: defaults to the dev-proxy path on `window.location`. */
  urlBuilder?: (sessionId: string, ticket: string) => string
  /** Test seam: defaults to the global timer. */
  setTimeout?: (handler: () => void, delayMs: number) => unknown
  /** Test seam: defaults to the global timer. */
  clearTimeout?: (handle: unknown) => void
  /** Test seam: defaults to `Date.now`. */
  now?: () => number
  /** Test seam: defaults to `crypto.randomUUID`. */
  generateCommandId?: () => string
}

export interface WsClient {
  connect(): Promise<void>
  send(type: ClientCommandType, payload?: EventPayload): boolean
  resume(): boolean
  close(): void
  lastSeq(): number
}

type Phase = 'idle' | 'connecting' | 'open' | 'reconnecting' | 'failed' | 'closed'

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function defaultSocketFactory(url: string): WsSocket {
  return new WebSocket(url) as unknown as WsSocket
}

/**
 * Builds the control-channel URL. In dev the Vite proxy forwards `/ws` to the
 * backend; in production the browser origin serves both paths.
 */
export function defaultWsUrlBuilder(sessionId: string, ticket: string): string {
  const location = typeof window === 'undefined' ? undefined : window.location
  const protocol = location?.protocol === 'https:' ? 'wss' : 'ws'
  const host = location?.host || 'localhost:8080'
  const path = `/ws/v1/sessions/${encodeURIComponent(sessionId)}`
  return `${protocol}://${host}${path}?ticket=${encodeURIComponent(ticket)}`
}

function defaultCommandId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

function parseEnvelope(raw: unknown): ServerEvent | null {
  if (typeof raw !== 'string') {
    return null
  }
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return null
  }
  if (!isRecord(parsed) || typeof parsed.type !== 'string') {
    return null
  }
  return parsed as unknown as ServerEvent
}

export function createWsClient(options: WsClientOptions): WsClient {
  const {
    sessionId,
    ticketProvider,
    onEvent,
    onStatus,
    recordingState,
    socketFactory = defaultSocketFactory,
    urlBuilder = defaultWsUrlBuilder,
    now = () => Date.now(),
    generateCommandId = defaultCommandId,
  } = options

  const schedule: (handler: () => void, delayMs: number) => unknown =
    options.setTimeout ?? ((handler, delayMs) => globalThis.setTimeout(handler, delayMs))
  const cancel: (handle: unknown) => void =
    options.clearTimeout ?? ((handle) => globalThis.clearTimeout(handle as number))

  let phase: Phase = 'idle'
  let socket: WsSocket | null = null
  let lastSeenSeq = 0
  let graceDeadline: number | null = null
  let countdownHandle: unknown = null
  let retryHandle: unknown = null
  let attemptInFlight = false

  function emitStatus(status: WsStatus): void {
    onStatus(status)
  }

  function clearReconnectTimers(): void {
    graceDeadline = null
    if (countdownHandle !== null) {
      cancel(countdownHandle)
      countdownHandle = null
    }
    if (retryHandle !== null) {
      cancel(retryHandle)
      retryHandle = null
    }
  }

  function send(type: ClientCommandType, payload: EventPayload = {}): boolean {
    const active = socket
    if (!active || phase !== 'open' || active.readyState !== WS_OPEN) {
      return false
    }
    const command: ClientCommand = {
      v: PROTOCOL_VERSION,
      type,
      command_id: generateCommandId(),
      payload,
    }
    active.send(JSON.stringify(command))
    return true
  }

  function sendResume(): boolean {
    return send('session.resume', {
      last_seq: lastSeenSeq,
      recording_state: recordingState(),
    })
  }

  function fail(reason: WsFailureReason): void {
    if (phase === 'failed' || phase === 'closed') {
      return
    }
    phase = 'failed'
    clearReconnectTimers()
    const active = socket
    socket = null
    if (active) {
      try {
        active.close(1000, 'client failed')
      } catch {
        // The failure status is what matters; a refusing socket is already gone.
      }
    }
    emitStatus({ status: 'failed', reason })
  }

  function handleMessage(raw: unknown, reconnecting: boolean): void {
    const event = parseEnvelope(raw)
    if (!event) {
      return
    }

    // Transport heartbeats are handled before dedupe: their seq mirrors the
    // last event seq known to the connection and must not move it.
    if (event.type === 'ping') {
      send('pong', {})
      return
    }

    // Control messages have no meaningful sequence of their own.
    if (event.type === 'server.ack' || event.type === 'error') {
      onEvent(event)
      return
    }

    if (event.type === 'session.snapshot') {
      // On a reconnect the server sends its current snapshot *before* it
      // processes `session.resume`. Seeding lastSeq from it would suppress the
      // replay tail that follows, so only the first connection seeds the
      // cursor; on reconnect the pre-disconnect cursor is kept.
      if (!reconnecting) {
        const payload = isRecord(event.payload) ? event.payload : {}
        const snapshotSeq = payload.last_seq
        if (typeof snapshotSeq === 'number' && Number.isFinite(snapshotSeq) && snapshotSeq >= 0) {
          lastSeenSeq = snapshotSeq
        }
      }
      onEvent(event)
      return
    }

    // `session.resumed` is appended *before* the replayed tail and therefore
    // carries a higher seq than the events that follow it. It must not move
    // lastSeq, otherwise the whole replay would be dropped.
    if (event.type === 'session.resumed') {
      clearReconnectTimers()
      onEvent(event)
      return
    }

    const seq = typeof event.seq === 'number' ? event.seq : undefined
    if (seq === undefined) {
      // Be tolerant of server control variants that omit seq.
      onEvent(event)
      return
    }
    if (seq <= lastSeenSeq) {
      return
    }
    lastSeenSeq = seq
    onEvent(event)
  }

  function connectSocket(ticket: string, resume: boolean): void {
    const next = socketFactory(urlBuilder(sessionId, ticket))
    socket = next

    next.onopen = () => {
      if (socket !== next) {
        return
      }
      phase = 'open'
      emitStatus({ status: 'connected' })
      if (resume) {
        sendResume()
      }
    }
    next.onmessage = (message) => {
      if (socket === next) {
        handleMessage(message.data, resume)
      }
    }
    next.onclose = () => {
      handleClose(next)
    }
    next.onerror = () => {
      // Browsers fire `close` after an error; reconnection is handled there.
    }
  }

  function scheduleRetry(): void {
    if (phase !== 'reconnecting' || retryHandle !== null) {
      return
    }
    retryHandle = schedule(() => {
      retryHandle = null
      attemptReconnect()
    }, WS_RECONNECT_RETRY_MS)
  }

  function scheduleCountdown(): void {
    countdownHandle = schedule(() => {
      countdownHandle = null
      if (phase !== 'reconnecting' || graceDeadline === null) {
        return
      }
      const remaining = graceDeadline - now()
      if (remaining <= 0) {
        fail('reconnect_timeout')
        return
      }
      emitStatus({ status: 'reconnecting', secondsLeft: Math.ceil(remaining / 1000) })
      scheduleCountdown()
    }, WS_RECONNECT_RETRY_MS)
  }

  function attemptReconnect(): void {
    if (phase !== 'reconnecting' || attemptInFlight || socket !== null) {
      return
    }
    if (recordingState() === 'lost') {
      fail('recording_lost')
      return
    }
    attemptInFlight = true
    void (async () => {
      try {
        const ticket = await ticketProvider()
        if (phase !== 'reconnecting') {
          return
        }
        if (recordingState() === 'lost') {
          fail('recording_lost')
          return
        }
        connectSocket(ticket, true)
      } catch {
        scheduleRetry()
      } finally {
        attemptInFlight = false
      }
    })()
  }

  function beginReconnect(): void {
    phase = 'reconnecting'
    graceDeadline = now() + WS_GRACE_MS
    emitStatus({ status: 'reconnecting', secondsLeft: Math.ceil(WS_GRACE_MS / 1000) })
    scheduleCountdown()
    attemptReconnect()
  }

  function handleClose(closedSocket: WsSocket): void {
    if (socket !== closedSocket) {
      return
    }
    socket = null
    if (phase === 'closed' || phase === 'failed') {
      return
    }
    if (recordingState() === 'lost') {
      fail('recording_lost')
      return
    }
    if (phase === 'reconnecting') {
      if (graceDeadline === null) {
        beginReconnect()
      } else {
        scheduleRetry()
      }
      return
    }
    beginReconnect()
  }

  async function openInitialSocket(): Promise<void> {
    try {
      const ticket = await ticketProvider()
      if (phase !== 'connecting') {
        return
      }
      connectSocket(ticket, false)
    } catch {
      fail('connect_failed')
    }
  }

  async function connect(): Promise<void> {
    if (phase !== 'idle') {
      return
    }
    phase = 'connecting'
    emitStatus({ status: 'connecting' })
    await openInitialSocket()
  }

  function resume(): boolean {
    if (phase !== 'open') {
      return false
    }
    return sendResume()
  }

  function close(): void {
    if (phase === 'closed') {
      return
    }
    phase = 'closed'
    clearReconnectTimers()
    const active = socket
    socket = null
    if (active) {
      active.close(1000, 'client closed')
    }
  }

  return {
    connect,
    send,
    resume,
    close,
    lastSeq: () => lastSeenSeq,
  }
}