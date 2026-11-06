import { afterAll, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { nextTick } from 'vue'
import ReplayPlayer from './ReplayPlayer.vue'
import type { ReplayEvent } from '../../api/replay'
import type { ReplayView } from '../../types/recording'

/**
 * jsdom does not implement media playback. Stubbing `play`/`pause` keeps the
 * test output pristine; the sync logic is exercised by assigning
 * `currentTime` directly, exactly like a media element would.
 */
const playSpy = vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined)
const pauseSpy = vi
  .spyOn(HTMLMediaElement.prototype, 'pause')
  .mockImplementation(() => undefined)

afterAll(() => {
  playSpy.mockRestore()
  pauseSpy.mockRestore()
})

function replayView(overrides: Partial<ReplayView> = {}): ReplayView {
  return {
    session_id: 'session-1',
    mode: 'free',
    duration_ms: 60_000,
    streams: [
      {
        stream_type: 'screen',
        url: 'https://media.test/screen.webm',
        offset_ms: 0,
        duration_ms: 60_000,
        mime: 'video/webm',
      },
      {
        stream_type: 'camera',
        url: 'https://media.test/camera.webm',
        offset_ms: 120,
        duration_ms: 59_880,
        mime: 'video/webm',
      },
      {
        stream_type: 'audio',
        url: 'https://media.test/audio.webm',
        offset_ms: 0,
        duration_ms: 60_000,
        mime: 'audio/webm',
      },
    ],
    ...overrides,
  }
}

function event(
  eventId: string,
  type: string,
  atMs: number,
): ReplayEvent {
  return { event_id: eventId, seq: atMs / 1000, type, at_ms: atMs, payload: {} }
}

function media<T extends HTMLMediaElement>(wrapper: VueWrapper, testId: string): T {
  return wrapper.get(`[data-testid="${testId}"]`).element as T
}

function fakeTimers(): void {
  vi.useFakeTimers({
    toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'],
  })
}

describe('ReplayPlayer', () => {
  it('uses audio as the master clock and corrects video drift beyond 200 ms', async () => {
    fakeTimers()
    let wrapper: VueWrapper | null = null
    try {
      wrapper = mount(ReplayPlayer, { props: { replay: replayView() } })
      await nextTick()

      const audio = media<HTMLAudioElement>(wrapper, 'replay-audio')
      const camera = media<HTMLVideoElement>(wrapper, 'replay-camera')
      const screen = media<HTMLVideoElement>(wrapper, 'replay-screen')

      await wrapper.get('[data-testid="replay-toggle"]').trigger('click')
      await vi.advanceTimersByTimeAsync(0)
      expect(playSpy).toHaveBeenCalled()

      // Audio is at 10 s. Camera has a 120 ms offset -> expected 9.88 s;
      // screen has no offset -> expected 10 s. Both are far out of sync.
      audio.currentTime = 10
      camera.currentTime = 0
      screen.currentTime = 10.4
      await vi.advanceTimersByTimeAsync(500)

      expect(camera.currentTime).toBeCloseTo(9.88, 2)
      expect(screen.currentTime).toBeCloseTo(10, 2)
      expect(wrapper.get('[data-testid="replay-time"]').text()).toContain('00:10')

      // 80 ms drift is inside the 200 ms tolerance: leave the stream alone.
      camera.currentTime = 9.8
      await vi.advanceTimersByTimeAsync(500)
      expect(camera.currentTime).toBeCloseTo(9.8, 2)

      // A video position never drives the displayed session clock.
      camera.currentTime = 30
      await vi.advanceTimersByTimeAsync(500)
      expect(wrapper.get('[data-testid="replay-time"]').text()).toContain('00:10')
      expect(camera.currentTime).toBeCloseTo(9.88, 2)
    } finally {
      wrapper?.unmount()
      vi.useRealTimers()
    }
  })

  it('renders event filters and seeks every stream on event click', async () => {
    const wrapper = mount(ReplayPlayer, {
      props: {
        replay: replayView(),
        events: [
          event('e1', 'teacher.called_on', 5000),
          event('e2', 'student.answer', 6000),
          event('e3', 'discipline.event', 7000),
          event('e4', 'interview.question_shown', 8000),
        ],
      },
    })
    await nextTick()

    for (const key of ['all', 'teacher', 'student', 'discipline', 'interview']) {
      expect(wrapper.find(`[data-testid="event-filter-${key}"]`).exists()).toBe(true)
    }

    await wrapper.get('[data-testid="event-filter-student"]').trigger('click')
    expect(wrapper.find('[data-testid="event-item-e2"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="event-item-e1"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="event-item-e3"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="event-item-e4"]').exists()).toBe(false)

    await wrapper.get('[data-testid="event-filter-discipline"]').trigger('click')
    expect(wrapper.find('[data-testid="event-item-e3"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="event-item-e2"]').exists()).toBe(false)

    const audio = media<HTMLAudioElement>(wrapper, 'replay-audio')
    const camera = media<HTMLVideoElement>(wrapper, 'replay-camera')
    const screen = media<HTMLVideoElement>(wrapper, 'replay-screen')

    await wrapper.get('[data-testid="event-item-e3"]').trigger('click')

    // 7 s session time -> audio/screen 7 s, camera (offset 120 ms) 6.88 s.
    expect(audio.currentTime).toBeCloseTo(7)
    expect(screen.currentTime).toBeCloseTo(7)
    expect(camera.currentTime).toBeCloseTo(6.88, 2)

    await wrapper.get('[data-testid="event-filter-all"]').trigger('click')
    expect(wrapper.find('[data-testid="event-item-e1"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('applies the initial ?t position to all streams', async () => {
    const wrapper = mount(ReplayPlayer, {
      props: { replay: replayView(), initialTimeMs: 5000 },
    })
    await nextTick()
    await nextTick()

    expect(media<HTMLAudioElement>(wrapper, 'replay-audio').currentTime).toBeCloseTo(5)
    expect(media<HTMLVideoElement>(wrapper, 'replay-camera').currentTime).toBeCloseTo(4.88, 2)
    expect(media<HTMLVideoElement>(wrapper, 'replay-screen').currentTime).toBeCloseTo(5)
    wrapper.unmount()
  })

  it('uses the camera as the main player in interview mode', async () => {
    const wrapper = mount(ReplayPlayer, {
      props: {
        replay: replayView({
          mode: 'interview',
          streams: replayView().streams.filter((stream) => stream.stream_type !== 'screen'),
        }),
      },
    })
    await nextTick()

    expect(wrapper.find('[data-testid="replay-camera"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="replay-screen"]').exists()).toBe(false)
    expect(
      wrapper.get('[data-testid="replay-player"]').find('.replay-player__stage--interview').exists(),
    ).toBe(true)
    wrapper.unmount()
  })
})