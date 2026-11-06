import { describe, expect, it, vi } from 'vitest'
import { PerceptionClient } from './perceptionClient'
import type { WsSocket } from './wsClient'

class FakeSocket implements WsSocket {
  readyState = 0
  sent: Array<string | ArrayBuffer> = []
  onopen: (() => void) | null = null
  onmessage: ((event: { data: unknown }) => void) | null = null
  onclose: ((event: { code: number; reason: string }) => void) | null = null
  onerror: ((event: unknown) => void) | null = null

  send(data: string | ArrayBuffer): void {
    this.sent.push(data)
  }

  close(): void {
    this.readyState = 3
    this.onclose?.({ code: 1000, reason: '' })
  }

  open(): void {
    this.readyState = 1
    this.onopen?.()
  }

  message(data: unknown): void {
    this.onmessage?.({ data })
  }
}

function setup() {
  const socket = new FakeSocket()
  const messages: unknown[] = []
  const statuses: string[] = []
  const client = new PerceptionClient({
    sessionId: 'session-1',
    ticketProvider: vi.fn(async () => 'ticket-1'),
    socketFactory: () => socket,
    urlBuilder: (id, ticket) => `ws://test/ws/v1/sessions/${id}/perception?ticket=${ticket}`,
    onMessage: (message) => messages.push(message),
    onStatus: (status) => statuses.push(status),
    setTimeout: (handler) => {
      handler()
      return 1
    },
  })
  return { client, socket, messages, statuses }
}

describe('PerceptionClient', () => {
  it('opens with audio.start and forwards raw PCM frames', async () => {
    const { client, socket, statuses } = setup()
    await client.connect()
    socket.open()

    expect(socket.sent[0]).toBe(
      JSON.stringify({
        type: 'audio.start',
        format: 'pcm16',
        sample_rate: 16000,
        channels: 1,
      }),
    )
    expect(client.sendAudio(new ArrayBuffer(4))).toBe(true)
    expect(socket.sent[1]).toBeInstanceOf(ArrayBuffer)
    expect(statuses).toContain('connected')
  })

  it('parses partial and final ASR messages and ignores malformed frames', async () => {
    const { client, socket, messages } = setup()
    await client.connect()
    socket.open()

    socket.message(JSON.stringify({ type: 'asr.partial', text: '你', at_ms: 10 }))
    socket.message(JSON.stringify({
      type: 'asr.final',
      text: '你好',
      text_id: 'text-1',
      at_ms: 20,
    }))
    socket.message('not json')

    expect(messages).toEqual([
      { type: 'asr.partial', text: '你', at_ms: 10 },
      { type: 'asr.final', text: '你好', text_id: 'text-1', at_ms: 20 },
    ])
  })

  it('sends audio.stop and does not reconnect after an explicit stop', async () => {
    const { client, socket, statuses } = setup()
    await client.connect()
    socket.open()

    const stop = client.stop()
    socket.onclose?.({ code: 1000, reason: '音频结束' })
    await stop

    expect(socket.sent).toContain(JSON.stringify({ type: 'audio.stop' }))
    expect(statuses).toContain('closed')
    expect(statuses).not.toContain('reconnecting')
  })
})
