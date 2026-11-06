import { afterAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ReplayPage from './ReplayPage.vue'
import { getEvents, getReplay } from '../../api/replay'
import type { ReplayEvent } from '../../api/replay'
import type { ReplayView } from '../../types/recording'

vi.mock('../../api/replay', () => ({
  getReplay: vi.fn(),
  getEvents: vi.fn(),
}))

// jsdom does not implement media playback; stub it so mounting the embedded
// player does not print "Not implemented" warnings.
const playSpy = vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined)
const pauseSpy = vi
  .spyOn(HTMLMediaElement.prototype, 'pause')
  .mockImplementation(() => undefined)

afterAll(() => {
  playSpy.mockRestore()
  pauseSpy.mockRestore()
})

const CONTROL_TYPES = [
  'session.time_tick',
  'session.resumed',
  'ping',
  'pong',
  'time.sync',
]

function replayView(): ReplayView {
  return {
    session_id: 'session-1',
    mode: 'free',
    duration_ms: 60_000,
    streams: [
      {
        stream_type: 'audio',
        url: 'https://media.test/audio.webm',
        offset_ms: 0,
        duration_ms: 60_000,
        mime: 'audio/webm',
      },
    ],
  }
}

function event(seq: number, type: string): ReplayEvent {
  return {
    event_id: `${type}-${seq}`,
    seq,
    type,
    at_ms: seq * 10,
    payload: {},
  }
}

function meaningful(seq: number): ReplayEvent {
  return event(seq, 'teacher.ask_class')
}

function tick(seq: number): ReplayEvent {
  return event(seq, 'session.time_tick')
}

function page(items: ReplayEvent[]) {
  return { items, total: 10_000, page: 0, size: items.length }
}

/** 50 meaningful rows and 50 control rows per full page. */
function mixedPage(startSeq: number): ReplayEvent[] {
  const items: ReplayEvent[] = []
  for (let offset = 0; offset < 100; offset += 1) {
    const seq = startSeq + offset
    items.push(offset % 2 === 0 ? meaningful(seq) : tick(seq))
  }
  return items
}

async function mountPage() {
  const wrapper = mount(ReplayPage, { props: { sessionId: 'session-1' } })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(getReplay).mockReset().mockResolvedValue(replayView())
  vi.mocked(getEvents).mockReset()
})

describe('ReplayPage event history', () => {
  it('filters control events and pages forward until 100 meaningful events', async () => {
    vi.mocked(getEvents).mockImplementation(async (_sessionId, afterSeq = 0) => {
      if (afterSeq === 0) {
        // A tick-heavy page that also contains every filtered control type.
        const items = mixedPage(1)
        items[1] = event(2, 'session.resumed')
        items[3] = event(4, 'ping')
        items[5] = event(6, 'pong')
        items[7] = event(8, 'time.sync')
        return page(items)
      }
      if (afterSeq === 100) {
        return page(mixedPage(101))
      }
      // One extra meaningful row proves the feed has more than the 100 shown.
      return page([meaningful(201)])
    })

    const wrapper = await mountPage()

    expect(getEvents).toHaveBeenNthCalledWith(1, 'session-1', 0, 100)
    expect(getEvents).toHaveBeenNthCalledWith(2, 'session-1', 100, 100)
    expect(getEvents).toHaveBeenNthCalledWith(3, 'session-1', 200, 100)

    // 101 meaningful rows were found; only the first 100 are rendered.
    expect(wrapper.findAll('[data-testid^="event-item-"]')).toHaveLength(100)
    expect(wrapper.find('[data-testid="event-item-teacher.ask_class-1"]').exists()).toBe(
      true,
    )
    expect(wrapper.find('[data-testid="event-item-teacher.ask_class-201"]').exists()).toBe(
      false,
    )
    for (const type of CONTROL_TYPES) {
      expect(wrapper.find(`[data-testid^="event-item-${type}-"]`).exists()).toBe(false)
    }

    expect(wrapper.find('[data-testid="events-truncated"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('does not show the truncation hint when the feed ends below the cap', async () => {
    vi.mocked(getEvents).mockImplementation(async (_sessionId, afterSeq = 0) => {
      if (afterSeq === 0) {
        const items = mixedPage(1)
        return page(items)
      }
      // A short last page ends the feed with only three meaningful rows total.
      return page([tick(101), meaningful(102)])
    })

    const wrapper = await mountPage()

    expect(getEvents).toHaveBeenCalledTimes(2)
    expect(wrapper.findAll('[data-testid^="event-item-"]')).toHaveLength(51)
    expect(wrapper.find('[data-testid="events-truncated"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('bounds the scan at 20 pages when the feed is all ticks', async () => {
    vi.mocked(getEvents).mockImplementation(async (_sessionId, afterSeq = 0) => {
      const items: ReplayEvent[] = []
      for (let offset = 0; offset < 100; offset += 1) {
        items.push(tick(afterSeq + offset + 1))
      }
      return page(items)
    })

    const wrapper = await mountPage()

    expect(getEvents).toHaveBeenCalledTimes(20)
    expect(wrapper.find('[data-testid="event-empty"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="events-truncated"]').exists()).toBe(false)
    wrapper.unmount()
  })
})