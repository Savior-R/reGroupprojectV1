import { afterAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { routes } from '../../app/router'
import ReviewPage from './ReviewPage.vue'
import { getSession } from '../../api/sessions'
import { getEvents, getRecordings, getReplay, submitSession } from '../../api/replay'
import type { SessionView } from '../../types/session'
import type { RecordingStreamView, ReplayView } from '../../types/recording'

vi.mock('../../api/sessions', () => ({
  createSession: vi.fn(),
  getSession: vi.fn(),
  startSession: vi.fn(),
  endSession: vi.fn(),
  cancelSession: vi.fn(),
}))

vi.mock('../../api/replay', () => ({
  getReplay: vi.fn(),
  getEvents: vi.fn(),
  getTexts: vi.fn(),
  getRecordings: vi.fn(),
  submitSession: vi.fn(),
}))

// jsdom does not implement media playback; stub it so mounting the replay
// player does not print "Not implemented" warnings.
const playSpy = vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined)
const pauseSpy = vi
  .spyOn(HTMLMediaElement.prototype, 'pause')
  .mockImplementation(() => undefined)

afterAll(() => {
  playSpy.mockRestore()
  pauseSpy.mockRestore()
})

function sessionView(overrides: Partial<SessionView> = {}): SessionView {
  return {
    session_id: 'session-1',
    task_id: 'task-1',
    course_id: 'course-1',
    session_type: 'task',
    mode: 'free',
    status: 'ended',
    attempt_no: 1,
    started_at: null,
    ended_at: null,
    duration_ms: 60_000,
    stream_offsets: {},
    last_seq: 0,
    ...overrides,
  }
}

function recording(
  streamType: RecordingStreamView['stream_type'],
  status: RecordingStreamView['status'],
  overrides: Partial<RecordingStreamView> = {},
): RecordingStreamView {
  return {
    stream_type: streamType,
    status,
    upload_id: 'upload-1',
    object_key: null,
    part_size: 5 * 1024 * 1024,
    parts_uploaded: status === 'uploaded' ? 3 : 1,
    bytes_uploaded: status === 'uploaded' ? 12 * 1024 * 1024 : 2 * 1024 * 1024,
    duration_ms: status === 'uploaded' ? 60_000 : null,
    offset_ms: 0,
    mime: 'video/webm',
    ...overrides,
  }
}

function uploadingFreeStreams(): RecordingStreamView[] {
  return [
    recording('camera', 'uploaded'),
    recording('screen', 'uploading'),
    recording('audio', 'uploaded'),
  ]
}

function uploadedFreeStreams(): RecordingStreamView[] {
  return [
    recording('camera', 'uploaded'),
    recording('screen', 'uploaded'),
    recording('audio', 'uploaded'),
  ]
}

function replayView(): ReplayView {
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
  }
}

function eventPage() {
  return { items: [], total: 0, page: 0, size: 100 }
}

function fakeTimers(): void {
  vi.useFakeTimers({
    toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'],
  })
}

async function mountPage(path = '/student/sessions/session-1/review') {
  const router = createRouter({ history: createMemoryHistory(), routes })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(ReviewPage, { global: { plugins: [router] } })
  await flushPromises()
  return { wrapper, router }
}

let replayLoadCount = 0

beforeEach(() => {
  vi.clearAllMocks()
  replayLoadCount = 0
  vi.mocked(getSession).mockReset().mockResolvedValue(sessionView())
  vi.mocked(getRecordings).mockReset()
  // Every fetch returns fresh URLs, like the real backend's presigned links,
  // so the player must remount its media elements on reload.
  vi.mocked(getReplay).mockReset().mockImplementation(async () => {
    replayLoadCount += 1
    const base = replayView()
    return {
      ...base,
      streams: base.streams.map((stream) => ({
        ...stream,
        url: `${stream.url}?v=${replayLoadCount}`,
      })),
    }
  })
  vi.mocked(getEvents).mockReset().mockResolvedValue(eventPage())
  vi.mocked(submitSession).mockReset()
})

describe('ReviewPage', () => {
  it('polls uploads and disables submit until required streams are uploaded', async () => {
    fakeTimers()
    let wrapper: VueWrapper | null = null
    try {
      vi.mocked(getRecordings)
        .mockResolvedValueOnce(uploadingFreeStreams())
        .mockResolvedValue(uploadedFreeStreams())

      const mounted = await mountPage()
      wrapper = mounted.wrapper

      expect(getSession).toHaveBeenCalledWith('session-1')
      expect(getRecordings).toHaveBeenCalledTimes(1)
      expect(
        wrapper.get('[data-testid="submit-session"]').attributes('disabled'),
      ).toBeDefined()
      expect(wrapper.get('[data-testid="stream-progress-screen"]').text()).toContain(
        '上传中',
      )
      expect(wrapper.find('[data-testid="replay-player"]').exists()).toBe(false)

      await vi.advanceTimersByTimeAsync(2_000)
      await flushPromises()

      expect(getRecordings).toHaveBeenCalledTimes(2)
      expect(
        wrapper.get('[data-testid="submit-session"]').attributes('disabled'),
      ).toBeUndefined()
      expect(wrapper.get('[data-testid="stream-progress-screen"]').text()).toContain(
        '已完成',
      )
      // The player only mounts once every required stream is uploaded.
      expect(getReplay).toHaveBeenCalledWith('session-1')
      expect(wrapper.find('[data-testid="replay-player"]').exists()).toBe(true)
    } finally {
      wrapper?.unmount()
      vi.useRealTimers()
    }
  })

  it('requires only camera and audio for interview sessions', async () => {
    vi.mocked(getSession).mockResolvedValue(sessionView({ mode: 'interview' }))
    vi.mocked(getRecordings).mockResolvedValue([
      recording('camera', 'uploaded'),
      recording('audio', 'uploaded'),
      // A stale screen row must not gate an interview submission.
      recording('screen', 'failed'),
    ])

    const { wrapper } = await mountPage()

    expect(
      wrapper.get('[data-testid="submit-session"]').attributes('disabled'),
    ).toBeUndefined()
    expect(wrapper.find('[data-testid="stream-progress-screen"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('submits and switches to read-only replay', async () => {
    vi.mocked(getRecordings).mockResolvedValue(uploadedFreeStreams())
    vi.mocked(submitSession).mockResolvedValue({
      submission_id: 'submission-1',
      task_id: 'task-1',
      session_id: 'session-1',
      student_account_id: 'student-1',
      status: 'pending_ai',
      submitted_at: '2026-11-06T10:00:00Z',
    })

    const { wrapper, router } = await mountPage()

    expect(getReplay).toHaveBeenCalledTimes(1)
    expect(
      wrapper.get('[data-testid="submit-session"]').attributes('disabled'),
    ).toBeUndefined()

    await wrapper.get('[data-testid="submit-session"]').trigger('click')
    await flushPromises()

    expect(submitSession).toHaveBeenCalledWith('session-1')
    // Initial load + post-submit reload with fresh presigned URLs.
    expect(getReplay).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="review-submitted"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="submit-session"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="redo-session"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('已提交')
    expect(wrapper.text()).toContain('submission-1')
    expect(router.currentRoute.value.path).toBe('/student/sessions/session-1/review')
    wrapper.unmount()
  })

  it('routes back to the task detail on redo', async () => {
    vi.mocked(getRecordings).mockResolvedValue(uploadedFreeStreams())

    const { wrapper, router } = await mountPage()

    await wrapper.get('[data-testid="redo-session"]').trigger('click')

    await vi.waitFor(() => {
      expect(router.currentRoute.value.path).toBe('/student/tasks/task-1')
    })
    wrapper.unmount()
  })

  it('applies the ?t query to the initial replay position', async () => {
    vi.mocked(getRecordings).mockResolvedValue(uploadedFreeStreams())

    const { wrapper } = await mountPage('/student/sessions/session-1/review?t=5000')

    const audio = wrapper.get('[data-testid="replay-audio"]').element as HTMLAudioElement
    expect(audio.currentTime).toBeCloseTo(5)
    wrapper.unmount()
  })

  it('preserves the replay position across the post-submit reload', async () => {
    vi.mocked(getRecordings).mockResolvedValue(uploadedFreeStreams())
    vi.mocked(submitSession).mockResolvedValue({
      submission_id: 'submission-1',
      task_id: 'task-1',
      session_id: 'session-1',
      student_account_id: 'student-1',
      status: 'pending_ai',
      submitted_at: '2026-11-06T10:00:00Z',
    })

    const { wrapper } = await mountPage()
    const before = wrapper.get('[data-testid="replay-audio"]').element as HTMLAudioElement
    before.currentTime = 12

    await wrapper.get('[data-testid="submit-session"]').trigger('click')
    await flushPromises()
    await flushPromises()

    const after = wrapper.get('[data-testid="replay-audio"]').element as HTMLAudioElement
    // Fresh presigned URLs remount the element; the position must survive.
    expect(after).not.toBe(before)
    expect(after.currentTime).toBeCloseTo(12)
    wrapper.unmount()
  })

  it('does not offer submission before the session has ended', async () => {
    vi.mocked(getSession).mockResolvedValue(sessionView({ status: 'in_progress' }))

    const { wrapper } = await mountPage()

    expect(wrapper.get('[data-testid="review-load-error"]').text()).toContain('尚未结束')
    expect(wrapper.find('[data-testid="submit-session"]').exists()).toBe(false)
    expect(getRecordings).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('keeps the player when only the event history request fails', async () => {
    vi.mocked(getRecordings).mockResolvedValue(uploadedFreeStreams())
    vi.mocked(getEvents).mockRejectedValue(new Error('事件服务不可用'))

    const { wrapper } = await mountPage()

    expect(wrapper.find('[data-testid="replay-player"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="replay-events-error"]').text()).toContain(
      '事件服务不可用',
    )
    expect(wrapper.find('[data-testid="replay-load-error"]').exists()).toBe(false)
    wrapper.unmount()
  })
})