import { afterAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { routes } from '../../app/router'
import InterviewPage from './InterviewPage.vue'
import { cancelSession, getSession } from '../../api/sessions'
import { acquireMedia, createRecorder } from '../../utils/media'
import type { ServerEvent, SessionView } from '../../types/session'
import type { StreamType } from '../../types/recording'
import type { UploadProgress } from '../../utils/recorderUploader'

interface FakeUploader {
  streamType: StreamType
  options: {
    onProgress?: (progress: UploadProgress) => void
    onError?: (error: Error) => void
  }
  start: ReturnType<typeof vi.fn>
  stop: ReturnType<typeof vi.fn>
  abort: ReturnType<typeof vi.fn>
}

/**
 * Module-boundary mocks: the page must be exercised against fake WS, media and
 * uploaders while using the real classroom reducer. Interview sessions only
 * ever acquire camera + microphone, so the fake media has no screen stream.
 */
const harness = vi.hoisted(() => {
  const uploaders: FakeUploader[] = []
  const state: { options: unknown } = { options: null }
  const fakeWs = {
    connect: vi.fn(async () => undefined),
    send: vi.fn((_type: string, _payload?: unknown) => true),
    resume: vi.fn(() => true),
    close: vi.fn(),
    lastSeq: vi.fn(() => 0),
  }
  return { uploaders, state, fakeWs }
})

vi.mock('../../utils/wsClient', () => ({
  WS_GRACE_MS: 30_000,
  createWsClient: vi.fn((options: unknown) => {
    harness.state.options = options
    return harness.fakeWs
  }),
}))

vi.mock('../../utils/recorderUploader', () => ({
  RecorderUploader: vi.fn(function (
    _api: unknown,
    _sessionId: string,
    streamType: StreamType,
    _recorder: unknown,
    options: FakeUploader['options'],
  ) {
    const uploader: FakeUploader = {
      streamType,
      options,
      start: vi.fn(async () => undefined),
      stop: vi.fn(async () => ({ stream_type: streamType })),
      abort: vi.fn(async () => undefined),
    }
    harness.uploaders.push(uploader)
    return uploader
  }),
}))

vi.mock('../../utils/perceptionClient', () => ({
  PerceptionClient: vi.fn(function () {
    return {
      connect: vi.fn(async () => undefined),
      sendAudio: vi.fn(() => true),
      stop: vi.fn(async () => undefined),
      close: vi.fn(),
    }
  }),
}))

vi.mock('../../utils/pcmCapture', () => ({
  PcmCapture: vi.fn(function () {
    return { start: vi.fn(async () => undefined), stop: vi.fn() }
  }),
}))

vi.mock('../../utils/screenSampler', () => ({
  ScreenSampler: vi.fn(function () {
    return { start: vi.fn(async () => undefined), stop: vi.fn() }
  }),
}))

vi.mock('../../utils/media', () => ({
  acquireMedia: vi.fn(),
  createRecorder: vi.fn(),
  STREAM_MIME: {
    camera: 'video/webm;codecs=vp8',
    screen: 'video/webm;codecs=vp8',
    audio: 'audio/webm;codecs=opus',
  },
}))

vi.mock('../../api/sessions', () => ({
  createSession: vi.fn(),
  getSession: vi.fn(),
  startSession: vi.fn(),
  endSession: vi.fn(),
  cancelSession: vi.fn(),
}))

vi.mock('../../api/replay', () => ({
  getTexts: vi.fn(async () => []),
}))

vi.mock('../../api/recordings', () => ({
  recordingsApi: {},
}))

vi.mock('../../api/wsTicket', () => ({
  fetchWsTicket: vi.fn(async () => 'ticket-1'),
}))

// jsdom does not implement HTMLMediaElement.play; stub it so the stream
// attachment code does not print "Not implemented" warnings.
const playSpy = vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined)
afterAll(() => {
  playSpy.mockRestore()
})

interface FakeMedia {
  camera: MediaStream
  audio: MediaStream
  stop: ReturnType<typeof vi.fn<() => void>>
}

function mediaStream(): MediaStream {
  return { getTracks: () => [] } as unknown as MediaStream
}

function acquiredMedia(): FakeMedia {
  return {
    camera: mediaStream(),
    audio: mediaStream(),
    stop: vi.fn(),
  }
}

function sessionView(overrides: Partial<SessionView> = {}): SessionView {
  return {
    session_id: 'session-1',
    task_id: 'task-1',
    course_id: 'course-1',
    session_type: 'task',
    mode: 'interview',
    status: 'preparing',
    attempt_no: 1,
    started_at: null,
    ended_at: null,
    duration_ms: null,
    stream_offsets: {},
    last_seq: 0,
    ...overrides,
  }
}

let seq = 0

function serverEvent(
  type: ServerEvent['type'],
  payload: Record<string, unknown>,
): ServerEvent {
  seq += 1
  return {
    v: 1,
    type,
    event_id: `event-${seq}`,
    seq,
    at_ms: seq * 1000,
    session_id: 'session-1',
    payload,
  }
}

interface SnapshotOptions {
  question?: {
    question_id: string
    order: number
    total: number
    text: string
    answer_points?: string[]
  }
  answerId?: string | null
  remainingMs?: number
  ended?: boolean
  lastSeq?: number
}

function snapshotEvent(
  state: 'preparing' | 'in_progress' = 'preparing',
  options: SnapshotOptions = {},
): ServerEvent {
  return serverEvent('session.snapshot', {
    state,
    mode: 'interview',
    public_config: { topic: '结构化面试', max_seconds: 600 },
    virtual_students: [],
    interview: {
      current: options.question ?? {
        question_id: 'q-1',
        order: 1,
        total: 3,
        text: '请做一段简单的自我介绍。',
      },
      answer_id: options.answerId ?? null,
      remaining_ms: options.remainingMs ?? 245_000,
      ended: options.ended ?? false,
    },
    recording: {
      required_streams: ['camera', 'audio'],
      streams: {},
      ready: false,
    },
    last_seq: options.lastSeq ?? 0,
    elapsed_ms: 0,
  })
}

function wsOptions(): {
  recordingState: () => 'active' | 'lost'
  onStatus: (status: unknown) => void
  onEvent: (event: ServerEvent) => void
} {
  return harness.state.options as {
    recordingState: () => 'active' | 'lost'
    onStatus: (status: unknown) => void
    onEvent: (event: ServerEvent) => void
  }
}

async function mountPage(options: { autoStart?: boolean } = {}) {
  const router = createRouter({ history: createMemoryHistory(), routes })
  await router.push('/student/sessions/session-1/interview')
  await router.isReady()
  const wrapper = mount(InterviewPage, { global: { plugins: [router] } })
  await flushPromises()
  if (options.autoStart !== false) {
    const start = wrapper.find('[data-testid="start-interview"]')
    if (start.exists()) {
      await start.trigger('click')
      await flushPromises()
    }
  }
  return { wrapper, router }
}

let currentMedia: FakeMedia

beforeEach(() => {
  vi.clearAllMocks()
  harness.uploaders.length = 0
  harness.state.options = null
  harness.fakeWs.send.mockReturnValue(true)

  currentMedia = acquiredMedia()
  vi.mocked(getSession).mockReset().mockResolvedValue(sessionView())
  vi.mocked(cancelSession).mockReset().mockResolvedValue(undefined)
  vi.mocked(acquireMedia).mockReset().mockResolvedValue(currentMedia)
  vi.mocked(createRecorder)
    .mockReset()
    .mockReturnValue({ state: 'recording', stop: vi.fn() } as unknown as MediaRecorder)
})

describe('InterviewPage', () => {
  it('requests only camera and audio', async () => {
    await mountPage()

    expect(getSession).toHaveBeenCalledWith('session-1')
    // `interview` is the only mode `acquireMedia` maps to camera+microphone;
    // it never calls getDisplayMedia, so no screen permission is requested.
    expect(acquireMedia).toHaveBeenCalledTimes(1)
    expect(acquireMedia).toHaveBeenCalledWith('interview')
    expect(createRecorder).toHaveBeenCalledTimes(2)
    expect(harness.uploaders.map((uploader) => uploader.streamType)).toEqual([
      'camera',
      'audio',
    ])

    const options = wsOptions()
    options.onEvent(snapshotEvent('preparing'))
    await flushPromises()

    const readyCalls = harness.fakeWs.send.mock.calls.filter(
      ([type]) => type === 'recording.ready',
    )
    expect(readyCalls).toHaveLength(1)
    expect(readyCalls[0]?.[1]).toEqual({
      streams: [
        { stream_type: 'camera', status: 'ready' },
        { stream_type: 'audio', status: 'ready' },
      ],
    })
  })

  it('renders current question and total timer', async () => {
    const { wrapper } = await mountPage()

    const options = wsOptions()
    options.onEvent(
      snapshotEvent('preparing', {
        question: {
          question_id: 'q-2',
          order: 2,
          total: 5,
          text: '请介绍一次你解决冲突的经历。',
        },
        remainingMs: 245_000,
      }),
    )
    await flushPromises()

    expect(wrapper.get('[data-testid="question-text"]').text()).toBe(
      '请介绍一次你解决冲突的经历。',
    )
    expect(wrapper.get('[data-testid="question-order"]').text()).toBe('第 2 / 5 题')
    expect(wrapper.get('[data-testid="interview-timer"]').text()).toContain('04:05')

    // The total remaining time is server-driven: only `session.time_tick`
    // moves it, never a local countdown.
    options.onEvent(
      serverEvent('session.time_tick', {
        elapsed_ms: 5_000,
        remaining_ms: 240_000,
      }),
    )
    await flushPromises()

    expect(wrapper.get('[data-testid="interview-timer"]').text()).toContain('04:00')
    expect(wrapper.get('[data-testid="interview-timer"]').text()).not.toContain('04:05')

    // `interview.question_shown` replaces the question. Privileged fields on
    // the payload (answer points) must never reach the DOM.
    options.onEvent(
      serverEvent('interview.question_shown', {
        question_id: 'q-3',
        order: 3,
        total: 5,
        text: '请描述你的职业规划。',
        answer_points: ['内部评分要点'],
      }),
    )
    await flushPromises()

    expect(wrapper.get('[data-testid="question-text"]').text()).toBe('请描述你的职业规划。')
    expect(wrapper.get('[data-testid="question-order"]').text()).toBe('第 3 / 5 题')
    expect(wrapper.text()).not.toContain('内部评分要点')
  })

  it('disables next on the last question', async () => {
    const { wrapper } = await mountPage()

    const next = () => wrapper.get('[data-testid="next-question"]')
    const end = () => wrapper.get('[data-testid="end-interview"]')

    // No snapshot yet: status is unknown, so both commands stay disabled.
    expect(next().attributes('disabled')).toBeDefined()
    expect(end().attributes('disabled')).toBeDefined()

    const options = wsOptions()
    options.onEvent(
      snapshotEvent('preparing', {
        question: { question_id: 'q-3', order: 3, total: 3, text: '最后一题。' },
      }),
    )
    await flushPromises()
    // Still outside `in_progress`.
    expect(next().attributes('disabled')).toBeDefined()
    expect(end().attributes('disabled')).toBeDefined()

    options.onEvent(
      snapshotEvent('in_progress', {
        question: { question_id: 'q-3', order: 3, total: 3, text: '最后一题。' },
        lastSeq: 5,
      }),
    )
    await flushPromises()

    // Last question: no successor. Ending the interview is still allowed.
    expect(next().attributes('disabled')).toBeDefined()
    expect(end().attributes('disabled')).toBeUndefined()
  })

  it('uses interview.next_question and interview.end commands', async () => {
    const { wrapper, router } = await mountPage()

    const options = wsOptions()
    options.onStatus({ status: 'connected' })
    options.onEvent(
      snapshotEvent('in_progress', {
        question: { question_id: 'q-1', order: 1, total: 3, text: '第一题。' },
        lastSeq: 3,
      }),
    )
    await flushPromises()

    await wrapper.get('[data-testid="next-question"]').trigger('click')
    await flushPromises()

    expect(harness.fakeWs.send).toHaveBeenCalledWith('interview.next_question', {})

    await wrapper.get('[data-testid="end-interview"]').trigger('click')
    await flushPromises()

    for (const uploader of harness.uploaders) {
      expect(uploader.stop).toHaveBeenCalledTimes(1)
    }
    expect(harness.fakeWs.send).toHaveBeenCalledWith('interview.end', {})
    expect(wrapper.find('[data-testid="interview-ending"]').exists()).toBe(true)

    options.onEvent(serverEvent('interview.ended', {}))
    await flushPromises()

    await vi.waitFor(() => {
      expect(router.currentRoute.value.path).toBe(
        '/student/sessions/session-1/review',
      )
    })
    expect(currentMedia.stop).toHaveBeenCalled()
  })

  it('starts uploaders only after the ready ack, then session.start', async () => {
    await mountPage()

    const options = wsOptions()
    options.onEvent(snapshotEvent('preparing'))
    // A duplicate snapshot (reconnect) must not re-report stream readiness.
    options.onEvent(snapshotEvent('preparing'))
    await flushPromises()

    const readyCalls = harness.fakeWs.send.mock.calls.filter(
      ([type]) => type === 'recording.ready',
    )
    expect(readyCalls).toHaveLength(1)
    for (const uploader of harness.uploaders) {
      expect(uploader.start).not.toHaveBeenCalled()
    }
    expect(harness.fakeWs.send).not.toHaveBeenCalledWith('session.start', {})

    options.onEvent(
      serverEvent('recording.ready_ack', {
        required_streams: ['camera', 'audio'],
        ok: true,
      }),
    )
    await flushPromises()

    for (const uploader of harness.uploaders) {
      expect(uploader.start).toHaveBeenCalledTimes(1)
    }
    expect(harness.fakeWs.send).toHaveBeenCalledWith('session.start', {})
  })

  it('routes to review on session.ended', async () => {
    const { router } = await mountPage()

    const options = wsOptions()
    options.onStatus({ status: 'connected' })
    options.onEvent(
      snapshotEvent('in_progress', {
        lastSeq: 4,
      }),
    )
    await flushPromises()

    options.onEvent(
      serverEvent('session.ended', { reason: 'timeout', duration_ms: 600_000 }),
    )
    await flushPromises()

    await vi.waitFor(() => {
      expect(router.currentRoute.value.path).toBe(
        '/student/sessions/session-1/review',
      )
    })
    expect(harness.fakeWs.close).toHaveBeenCalled()
    expect(currentMedia.stop).toHaveBeenCalled()
  })

  it('reports a lost recording on reload without capturing media or uploaders', async () => {
    vi.mocked(getSession)
      .mockReset()
      .mockResolvedValue(sessionView({ status: 'in_progress' }))

    const { wrapper } = await mountPage()

    expect(acquireMedia).not.toHaveBeenCalled()
    expect(createRecorder).not.toHaveBeenCalled()
    expect(harness.uploaders).toHaveLength(0)

    const options = wsOptions()
    expect(options.recordingState()).toBe('lost')
    expect(harness.fakeWs.resume).not.toHaveBeenCalled()

    options.onStatus({ status: 'connected' })
    expect(harness.fakeWs.resume).toHaveBeenCalledTimes(1)
    expect(harness.fakeWs.send).not.toHaveBeenCalledWith('recording.ready', expect.anything())
    expect(harness.fakeWs.send).not.toHaveBeenCalledWith('session.start', {})

    options.onEvent(
      serverEvent('session.failed', {
        reason: 'recording_lost',
        error_code: 'SESSION_FAILED',
      }),
    )
    await flushPromises()

    expect(wrapper.text()).toContain('会话失败')
    expect(harness.fakeWs.close).toHaveBeenCalled()
  })

  it('shows a failure state when a track ends', async () => {
    const { wrapper } = await mountPage()

    const options = wsOptions()
    options.onEvent(
      snapshotEvent('in_progress', {
        lastSeq: 6,
      }),
    )
    await flushPromises()

    harness.uploaders[0]!.options.onError?.(new Error('媒体轨道已结束，录制失败'))
    await flushPromises()

    expect(wrapper.text()).toContain('媒体轨道已结束')
    expect(
      wrapper.get('[data-testid="next-question"]').attributes('disabled'),
    ).toBeDefined()
    expect(
      wrapper.get('[data-testid="end-interview"]').attributes('disabled'),
    ).toBeDefined()

    for (const item of harness.uploaders) {
      expect(item.abort).toHaveBeenCalled()
    }
    expect(harness.fakeWs.close).toHaveBeenCalled()
    expect(currentMedia.stop).toHaveBeenCalled()
  })

  it('keeps the page with an error when the end command is rejected', async () => {
    const { wrapper, router } = await mountPage()

    const options = wsOptions()
    options.onStatus({ status: 'connected' })
    options.onEvent(
      snapshotEvent('in_progress', {
        lastSeq: 7,
      }),
    )
    await flushPromises()

    await wrapper.get('[data-testid="end-interview"]').trigger('click')
    await flushPromises()
    expect(harness.fakeWs.send).toHaveBeenCalledWith('interview.end', {})

    options.onEvent(
      serverEvent('error', {
        code: 'SESSION_STATE_INVALID',
        message: '当前状态不允许该操作',
        command_id: 'cmd-1',
      }),
    )
    await flushPromises()

    expect(wrapper.text()).toContain('结束面试失败')
    expect(wrapper.text()).toContain('当前状态不允许该操作')
    expect(router.currentRoute.value.path).toBe(
      '/student/sessions/session-1/interview',
    )
    expect(wrapper.find('[data-testid="interview-ending"]').exists()).toBe(false)
    expect(
      wrapper.get('[data-testid="end-interview"]').attributes('disabled'),
    ).toBeUndefined()
    expect(currentMedia.stop).toHaveBeenCalled()
    for (const uploader of harness.uploaders) {
      expect(uploader.abort).not.toHaveBeenCalled()
    }
  })

  it('times out waiting for the terminal event without getting stuck', async () => {
    const { wrapper, router } = await mountPage()

    const options = wsOptions()
    options.onStatus({ status: 'connected' })
    options.onEvent(
      snapshotEvent('in_progress', {
        lastSeq: 8,
      }),
    )
    await flushPromises()

    vi.useFakeTimers()
    try {
      await wrapper.get('[data-testid="end-interview"]').trigger('click')
      await vi.advanceTimersByTimeAsync(10_000)
      await nextTick()
    } finally {
      vi.useRealTimers()
    }

    expect(wrapper.text()).toContain('结束面试超时')
    expect(router.currentRoute.value.path).toBe(
      '/student/sessions/session-1/interview',
    )
    expect(wrapper.find('[data-testid="interview-ending"]').exists()).toBe(false)
    expect(
      wrapper.get('[data-testid="end-interview"]').attributes('disabled'),
    ).toBeUndefined()
    expect(currentMedia.stop).toHaveBeenCalled()
    for (const uploader of harness.uploaders) {
      expect(uploader.abort).not.toHaveBeenCalled()
    }
  })

  it('waits for the start click before acquiring media, then runs the ready/start flow', async () => {
    const { wrapper } = await mountPage({ autoStart: false })

    expect(getSession).toHaveBeenCalledWith('session-1')
    expect(acquireMedia).not.toHaveBeenCalled()
    expect(createRecorder).not.toHaveBeenCalled()
    expect(harness.uploaders).toHaveLength(0)
    expect(harness.state.options).toBeNull()

    const gate = wrapper.get('[data-testid="interview-gate"]')
    expect(gate.text()).toContain('摄像头')
    expect(gate.text()).toContain('麦克风')
    expect(wrapper.find('[data-testid="end-interview"]').exists()).toBe(false)

    await wrapper.get('[data-testid="start-interview"]').trigger('click')
    await flushPromises()

    expect(acquireMedia).toHaveBeenCalledWith('interview')
    expect(createRecorder).toHaveBeenCalledTimes(2)
    expect(harness.state.options).toBeTruthy()
    expect(wrapper.find('[data-testid="interview-gate"]').exists()).toBe(false)

    const options = wsOptions()
    options.onEvent(snapshotEvent('preparing'))
    await flushPromises()
    expect(harness.fakeWs.send).toHaveBeenCalledWith('recording.ready', {
      streams: [
        { stream_type: 'camera', status: 'ready' },
        { stream_type: 'audio', status: 'ready' },
      ],
    })

    options.onEvent(
      serverEvent('recording.ready_ack', {
        required_streams: ['camera', 'audio'],
        ok: true,
      }),
    )
    await flushPromises()
    for (const uploader of harness.uploaders) {
      expect(uploader.start).toHaveBeenCalledTimes(1)
    }
    expect(harness.fakeWs.send).toHaveBeenCalledWith('session.start', {})
  })

  it('cancels a preparing session when media acquisition is denied', async () => {
    vi.mocked(acquireMedia).mockRejectedValue(new Error('用户拒绝了麦克风权限'))

    const { wrapper } = await mountPage()

    expect(cancelSession).toHaveBeenCalledWith('session-1')
    expect(wrapper.get('[data-testid="interview-failure"]').text()).toContain(
      '用户拒绝了麦克风权限',
    )
    expect(harness.state.options).toBeNull()
  })

  it('cancels a preparing session when the control channel cannot connect', async () => {
    const { wrapper } = await mountPage()

    wsOptions().onStatus({ status: 'failed', reason: 'connect_failed' })
    await flushPromises()

    expect(cancelSession).toHaveBeenCalledWith('session-1')
    expect(wrapper.get('[data-testid="interview-failure"]').text()).toContain(
      '无法连接面试服务',
    )
  })

  it('cancels a preparing session when leaving the start gate', async () => {
    const { wrapper } = await mountPage({ autoStart: false })

    expect(acquireMedia).not.toHaveBeenCalled()
    wrapper.unmount()
    await flushPromises()

    expect(cancelSession).toHaveBeenCalledWith('session-1')
  })

  it('does not cancel a session that is already in progress on reload', async () => {
    vi.mocked(getSession)
      .mockReset()
      .mockResolvedValue(sessionView({ status: 'in_progress' }))

    const { wrapper } = await mountPage()

    wrapper.unmount()
    await flushPromises()

    expect(cancelSession).not.toHaveBeenCalled()
  })

  it('arms the unload guard while recorders run and removes it when they stop', async () => {
    const addSpy = vi.spyOn(window, 'addEventListener')
    const removeSpy = vi.spyOn(window, 'removeEventListener')
    try {
      const { wrapper } = await mountPage()
      expect(addSpy.mock.calls.some(([type]) => type === 'beforeunload')).toBe(true)

      const options = wsOptions()
      options.onStatus({ status: 'connected' })
      options.onEvent(
        snapshotEvent('in_progress', {
          lastSeq: 5,
        }),
      )
      await flushPromises()

      await wrapper.get('[data-testid="end-interview"]').trigger('click')
      await flushPromises()

      expect(removeSpy.mock.calls.some(([type]) => type === 'beforeunload')).toBe(true)

      options.onEvent(serverEvent('interview.ended', {}))
      await flushPromises()
      wrapper.unmount()
    } finally {
      addSpy.mockRestore()
      removeSpy.mockRestore()
    }
  })
})