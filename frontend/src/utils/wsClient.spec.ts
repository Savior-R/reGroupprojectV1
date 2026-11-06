import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ClientCommand, ServerEvent } from '../types/session'
import type { WsSocket, WsStatus } from './wsClient'
import { createWsClient } from './wsClient'

/** Minimal fake of the browser WebSocket used by the client. */
class FakeSocket implements WsSocket {
  readyState = 0
  sent: string[] = []
  url: string
  onopen: (() => void) | null = null
  onmessage: ((event: { data: unknown }) => void) | null = null
  onclose: ((event: { code: number; reason: string }) => void) | null = null
  onerror: ((event: unknown) => void) | null = null

  constructor(url: string) {
    this.url = url
  }

  send(data: string): void {
    this.sent.push(data)
  }

  close(code = 1000, reason = ''): void {
    this.readyState = 3
    this.onclose?.({ code, reason })
  }

  /** Simulates a successful handshake. */
  open(): void {
    this.readyState = 1
    this.onopen?.()
  }

  /** Simulates a server frame. */
  receive(event: unknown): void {
    this.onmessage?.({ data: JSON.stringify(event) })
  }

  /** Simulates an unexpected connection drop. */
  serverClose(code = 1006, reason = 'network'): void {
    this.readyState = 3
    this.onclose?.({ code, reason })
  }
}

let eventId = 0

function serverEvent(
  type: ServerEvent['type'],
  seq: number | undefined,
  payload: Record<string, unknown> = {},
): ServerEvent {
  eventId += 1
  return {
    v: 1,
    type,
    event_id: `event-${eventId}`,
    seq: seq as number,
    at_ms: (seq ?? 0) * 100,
    session_id: 'session-1',
    payload,
  }
}

/** Control envelopes (`server.ack` / `error`) without a `seq`. */
function controlEvent(
  type: 'server.ack' | 'error',
  payload: Record<string, unknown>,
): ServerEvent {
  eventId += 1
  return {
    v: 1,
    type,
    event_id: `event-${eventId}`,
    at_ms: 0,
    session_id: 'session-1',
    payload,
  } as ServerEvent
}

interface Harness {
  client: ReturnType<typeof createWsClient>
  sockets: FakeSocket[]
  statuses: WsStatus[]
  events: ServerEvent[]
  ticketProvider: ReturnType<typeof vi.fn<() => Promise<string>>>
}

function setup(options: { recording?: 'active' | 'lost' } = {}): Harness {
  const sockets: FakeSocket[] = []
  const statuses: WsStatus[] = []
  const events: ServerEvent[] = []
  let ticketNumber = 0
  const ticketProvider = vi.fn(async () => {
    ticketNumber += 1
    return `ticket-${ticketNumber}`
  })
  let commandNumber = 0

  const client = createWsClient({
    sessionId: 'session-1',
    ticketProvider,
    onEvent: (event) => events.push(event),
    onStatus: (status) => statuses.push(status),
    recordingState: () => options.recording ?? 'active',
    socketFactory: (url) => {
      const socket = new FakeSocket(url)
      sockets.push(socket)
      return socket
    },
    now: () => Date.now(),
    generateCommandId: () => {
      commandNumber += 1
      return `command-${commandNumber}`
    },
  })

  return { client, sockets, statuses, events, ticketProvider }
}

function sentCommands(socket: FakeSocket): ClientCommand[] {
  return socket.sent.map((raw) => JSON.parse(raw) as ClientCommand)
}

describe('wsClient', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(0)
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('requests a ticket and opens the control WebSocket URL', async () => {
    const harness = setup()

    await harness.client.connect()

    expect(harness.ticketProvider).toHaveBeenCalledTimes(1)
    expect(harness.sockets).toHaveLength(1)
    expect(harness.sockets[0]!.url).toContain('/ws/v1/sessions/session-1?ticket=ticket-1')
    expect(harness.statuses).toEqual([{ status: 'connecting' }])

    harness.sockets[0]!.open()
    expect(harness.statuses.at(-1)).toEqual({ status: 'connected' })
  })

  it('drops duplicate and out-of-order seq values', async () => {
    const harness = setup()
    await harness.client.connect()
    const socket = harness.sockets[0]!
    socket.open()

    for (const seq of [1, 2, 2, 1, 3]) {
      socket.receive(serverEvent('student.state_changed', seq, { student_id: 's1', to: 'focused' }))
    }

    expect(harness.events.map((event) => event.seq)).toEqual([1, 2, 3])
    expect(harness.client.lastSeq()).toBe(3)
  })

  it('seeds last_seq from session.snapshot', async () => {
    const harness = setup()
    await harness.client.connect()
    const socket = harness.sockets[0]!
    socket.open()

    socket.receive(serverEvent('session.snapshot', 42, { last_seq: 42, state: 'in_progress' }))
    expect(harness.client.lastSeq()).toBe(42)

    socket.receive(serverEvent('student.state_changed', 42, { student_id: 's1' }))
    socket.receive(serverEvent('student.state_changed', 43, { student_id: 's1' }))

    expect(harness.events.map((event) => event.seq)).toEqual([42, 43])
    expect(harness.client.lastSeq()).toBe(43)
  })

  it('responds to ping with a pong command without moving lastSeq', async () => {
    const harness = setup()
    await harness.client.connect()
    const socket = harness.sockets[0]!
    socket.open()

    socket.receive(serverEvent('ping', 7, { server_time_ms: 1234 }))

    expect(harness.client.lastSeq()).toBe(0)
    expect(sentCommands(socket)).toEqual([
      {
        v: 1,
        type: 'pong',
        command_id: 'command-1',
        payload: {},
      },
    ])

    // A normal event below the ping seq is still handled (ping is not a sequence source).
    socket.receive(serverEvent('student.state_changed', 1, { student_id: 's1' }))
    expect(harness.events).toHaveLength(1)
  })

  it('handles control messages without a seq', async () => {
    const harness = setup()
    await harness.client.connect()
    const socket = harness.sockets[0]!
    socket.open()

    socket.receive(controlEvent('server.ack', { command_id: 'c1', ok: true }))
    socket.receive(controlEvent('error', { code: 'SESSION_STATE_INVALID', message: 'bad' }))

    expect(harness.events.map((event) => event.type)).toEqual(['server.ack', 'error'])
  })

  it('reconnects with the last seen last_seq within grace', async () => {
    const harness = setup()
    await harness.client.connect()
    const first = harness.sockets[0]!
    first.open()
    first.receive(serverEvent('student.hand_raised', 5, { student_id: 's1', hand_raise_id: 'h1' }))

    first.serverClose(1006)

    expect(harness.statuses).toContainEqual({ status: 'reconnecting', secondsLeft: 30 })

    await vi.advanceTimersByTimeAsync(0)

    expect(harness.ticketProvider).toHaveBeenCalledTimes(2)
    expect(harness.sockets).toHaveLength(2)
    expect(harness.sockets[1]!.url).toContain('ticket-2')

    const second = harness.sockets[1]!
    second.open()

    expect(harness.statuses.at(-1)).toEqual({ status: 'connected' })
    expect(sentCommands(second)[0]).toEqual({
      v: 1,
      type: 'session.resume',
      command_id: 'command-1',
      payload: { last_seq: 5, recording_state: 'active' },
    })

    // session.resumed carries a higher seq, but the replayed events below it must survive.
    second.receive(serverEvent('session.resumed', 8, { last_seq: 5, server_time_ms: 100 }))
    second.receive(serverEvent('student.state_changed', 6, { student_id: 's1', to: 'called_on' }))
    second.receive(serverEvent('student.state_changed', 7, { student_id: 's1', to: 'answering' }))

    expect(harness.events.filter((event) => event.seq === 6 || event.seq === 7)).toHaveLength(2)
    expect(harness.client.lastSeq()).toBe(7)

    // A successful resume cancels the grace countdown.
    await vi.advanceTimersByTimeAsync(30_000)
    expect(harness.statuses.some((status) => status.status === 'failed')).toBe(false)
  })

  it('fails instead of reconnecting when recording_state is lost', async () => {
    const harness = setup({ recording: 'lost' })
    await harness.client.connect()
    const socket = harness.sockets[0]!
    socket.open()

    socket.serverClose(1006)

    expect(harness.ticketProvider).toHaveBeenCalledTimes(1)
    expect(harness.sockets).toHaveLength(1)
    expect(harness.statuses.at(-1)).toEqual({ status: 'failed', reason: 'recording_lost' })
  })

  it('fails when the 30 second reconnect grace expires', async () => {
    const harness = setup()
    await harness.client.connect()
    const socket = harness.sockets[0]!
    socket.open()

    socket.serverClose(1006)
    await vi.advanceTimersByTimeAsync(30_000)

    expect(harness.statuses.at(-1)).toEqual({ status: 'failed', reason: 'reconnect_timeout' })
    expect(harness.statuses.some((status) => status.status === 'failed')).toBe(true)
  })

  it('does not reconnect after a deliberate close', async () => {
    const harness = setup()
    await harness.client.connect()
    const socket = harness.sockets[0]!
    socket.open()

    harness.client.close()
    await vi.advanceTimersByTimeAsync(30_000)

    expect(harness.ticketProvider).toHaveBeenCalledTimes(1)
    expect(harness.statuses.some((status) => status.status === 'reconnecting')).toBe(false)
  })

  it('sends client commands with the protocol envelope', async () => {
    const harness = setup()
    await harness.client.connect()
    const socket = harness.sockets[0]!
    socket.open()

    const sent = harness.client.send('time.sync', { client_time_ms: 123 })

    expect(sent).toBe(true)
    expect(sentCommands(socket)).toEqual([
      {
        v: 1,
        type: 'time.sync',
        command_id: 'command-1',
        payload: { client_time_ms: 123 },
      },
    ])
  })
})