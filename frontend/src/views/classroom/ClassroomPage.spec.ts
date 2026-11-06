import { afterAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { routes } from '../../app/router'
import ClassroomPage from './ClassroomPage.vue'
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
 * uploaders while using the real classroom reducer.
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

vi.mock('../../api/recordings', () => ({
  recordingsApi: {},
}))

// jsdom does not implement HTMLMediaElement.play; stub it so the stream
// attachment code does not print "Not implemented" warnings.
const playSpy = vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined)
afterAll(() => {
  playSpy.mockRestore()
})

interface FakeMedia {
  camera: MediaStream
  screen: MediaStream
  audio: MediaStream
  stop: ReturnType<typeof vi.fn<() => void>>
}

function mediaStream(): MediaStream {
  return { getTracks: () => [] } as unknown as MediaStream
}

function acquiredMedia(): FakeMedia {
  return {
    camera: mediaStream(),
    screen: mediaStream(),
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
    mode: 'free',
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

function snapshotEvent(
  state: 'preparing' | 'in_progress' = 'preparing',
  lastSeq = 0,
): ServerEvent {
  return serverEvent('session.snapshot', {
    state,
    mode: 'free',
    public_config: { topic: '春天', max_seconds: 300 },
    virtual_students: [
      {
        virtual_student_id: 'vs-1',
        name: '小明',
        avatar: null,
        seat_no: 1,
        sort_order: 1,
        state: 'hand_raised',
        attention: 0.5,
        hand_raise_energy: 0.9,
      },
      {
        virtual_student_id: 'vs-2',
        name: '小红',
        avatar: null,
        seat_no: 2,
        sort_order: 2,
        state: 'focused',
        attention: 0.7,
        hand_raise_energy: 0.1,
      },
    ],
    interview: {},
    recording: {
      required_streams: ['camera', 'screen', 'audio'],
      streams: {},
      ready: false,
    },
    last_seq: lastSeq,
    elapsed_ms: 0,
  })
}

async function mountPage(options: { autoStart?: boolean } = {}) {
  const router = createRouter({ history: createMemoryHistory(), routes })
  await router.push('/student/sessions/session-1/classroom')
  await router.isReady()
  const wrapper = mount(ClassroomPage, { global: { plugins: [router] } })
  await flushPromises()
  if (options.autoStart !== false) {
    const start = wrapper.find('[data-testid="start-recording"]')
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

describe('ClassroomPage', () => {
  it('sends recording.ready once, starts uploaders only after the ack, then session.start', async () => {
    const { wrapper } = await mountPage()

    expect(getSession).toHaveBeenCalledWith('session-1')
    expect(acquireMedia).toHaveBeenCalledWith('free')
    expect(harness.uploaders.map((uploader) => uploader.streamType)).toEqual([
      'camera',
      'screen',
      'audio',
    ])
    for (const uploader of harness.uploaders) {
      expect(uploader.start).not.toHaveBeenCalled()
    }
    expect(harness.state.options).toBeTruthy()

    const options = harness.state.options as {
      onEvent: (event: ServerEvent) => void
    }
    options.onEvent(snapshotEvent('preparing'))
    // A duplicate snapshot (reconnect) must not re-report stream readiness.
    options.onEvent(snapshotEvent('preparing'))
    await flushPromises()

    const readyCalls = harness.fakeWs.send.mock.calls.filter(
      ([type]) => type === 'recording.ready',
    )
    expect(readyCalls).toHaveLength(1)
    expect(readyCalls[0]?.[1]).toEqual({
      streams: [
        { stream_type: 'camera', status: 'ready' },
        { stream_type: 'screen', status: 'ready' },
        { stream_type: 'audio', status: 'ready' },
      ],
    })
    expect(harness.fakeWs.send).not.toHaveBeenCalledWith('session.start', {})
    for (const uploader of harness.uploaders) {
      expect(uploader.start).not.toHaveBeenCalled()
    }
    expect(wrapper.text()).toContain('小明')
    expect(wrapper.text()).toContain('小红')
    expect(wrapper.text()).toContain('向全班提问')

    options.onEvent(
      serverEvent('recording.ready_ack', {
        required_streams: ['camera', 'screen', 'audio'],
        ok: true,
      }),
    )
    await flushPromises()

    for (const uploader of harness.uploaders) {
      expect(uploader.start).toHaveBeenCalledTimes(1)
    }
    expect(harness.fakeWs.send).toHaveBeenCalledWith('session.start', {})
  })

  it('ends the session and stops recorders before routing to review', async () => {
    const { wrapper, router } = await mountPage()

    const options = harness.state.options as {
      onStatus: (status: unknown) => void
      onEvent: (event: ServerEvent) => void
    }
    options.onStatus({ status: 'connected' })
    options.onEvent(snapshotEvent('in_progress', 5))
    await flushPromises()

    const endButton = wrapper.get('[data-testid="end-session"]')
    expect(endButton.attributes('disabled')).toBeUndefined()

    await endButton.trigger('click')
    await flushPromises()

    for (const uploader of harness.uploaders) {
      expect(uploader.stop).toHaveBeenCalledTimes(1)
    }
    expect(harness.fakeWs.send).toHaveBeenCalledWith('teacher.end_session', {})

    options.onEvent(
      serverEvent('session.ended', { reason: 'manual', duration_ms: 12_345 }),
    )
    await flushPromises()

    await vi.waitFor(() => {
      expect(router.currentRoute.value.path).toBe(
        '/student/sessions/session-1/review',
      )
    })
    expect(currentMedia.stop).toHaveBeenCalled()
  })

  it('does not start the session when multipart upload start fails', async () => {
    const { wrapper } = await mountPage()

    harness.uploaders[0]!.start.mockRejectedValue(new Error('无法开始分片上传'))

    const options = harness.state.options as {
      onEvent: (event: ServerEvent) => void
    }
    options.onEvent(snapshotEvent('preparing'))
    await flushPromises()
    options.onEvent(
      serverEvent('recording.ready_ack', {
        required_streams: ['camera', 'screen', 'audio'],
        ok: true,
      }),
    )
    await flushPromises()

    expect(wrapper.text()).toContain('无法开始分片上传')
    expect(harness.fakeWs.send).not.toHaveBeenCalledWith('session.start', {})
    for (const uploader of harness.uploaders) {
      expect(uploader.abort).toHaveBeenCalled()
    }
  })

  it('retries session.start once the connection returns', async () => {
    const { wrapper } = await mountPage()

    let startAttempts = 0
    harness.fakeWs.send.mockImplementation((type: string) => {
      if (type !== 'session.start') {
        return true
      }
      startAttempts += 1
      return startAttempts > 1
    })

    const options = harness.state.options as {
      onStatus: (status: unknown) => void
      onEvent: (event: ServerEvent) => void
    }
    options.onEvent(snapshotEvent('preparing'))
    await flushPromises()
    options.onEvent(
      serverEvent('recording.ready_ack', {
        required_streams: ['camera', 'screen', 'audio'],
        ok: true,
      }),
    )
    await flushPromises()

    // The first send returned false and the page is waiting for the channel.
    expect(startAttempts).toBe(1)
    expect(wrapper.text()).not.toContain('无法开始试讲')

    options.onStatus({ status: 'connected' })
    await flushPromises()

    expect(startAttempts).toBe(2)
    expect(wrapper.text()).not.toContain('无法开始试讲')
  })

  it('waits for a reconnecting control channel before sending the end command', async () => {
    const { wrapper } = await mountPage()

    const options = harness.state.options as {
      onStatus: (status: unknown) => void
      onEvent: (event: ServerEvent) => void
    }
    options.onEvent(snapshotEvent('in_progress', 10))
    options.onStatus({ status: 'reconnecting', secondsLeft: 17 })
    await flushPromises()

    await wrapper.get('[data-testid="end-session"]').trigger('click')
    await flushPromises()

    expect(harness.fakeWs.send).not.toHaveBeenCalledWith('teacher.end_session', {})
    for (const uploader of harness.uploaders) {
      expect(uploader.stop).toHaveBeenCalledTimes(1)
      expect(uploader.abort).not.toHaveBeenCalled()
    }

    options.onStatus({ status: 'connected' })
    await flushPromises()

    expect(harness.fakeWs.send).toHaveBeenCalledWith('teacher.end_session', {})

    // Clear the 10 s end watchdog: the session ends before it fires.
    options.onEvent(serverEvent('session.ended', { reason: 'manual', duration_ms: 1_000 }))
    await flushPromises()
    wrapper.unmount()
  })

  it('keeps the page with an error when the end command is rejected', async () => {
    const { wrapper, router } = await mountPage()

    const options = harness.state.options as {
      onStatus: (status: unknown) => void
      onEvent: (event: ServerEvent) => void
    }
    options.onStatus({ status: 'connected' })
    options.onEvent(snapshotEvent('in_progress', 6))
    await flushPromises()

    await wrapper.get('[data-testid="end-session"]').trigger('click')
    await flushPromises()
    expect(harness.fakeWs.send).toHaveBeenCalledWith('teacher.end_session', {})

    options.onEvent(
      serverEvent('error', {
        code: 'SESSION_STATE_INVALID',
        message: '当前状态不允许该操作',
        command_id: 'cmd-1',
      }),
    )
    await flushPromises()

    expect(wrapper.text()).toContain('结束试讲失败')
    expect(wrapper.text()).toContain('当前状态不允许该操作')
    expect(router.currentRoute.value.path).toBe(
      '/student/sessions/session-1/classroom',
    )
    expect(wrapper.find('[data-testid="classroom-ending"]').exists()).toBe(false)
    expect(
      wrapper.get('[data-testid="end-session"]').attributes('disabled'),
    ).toBeUndefined()
    expect(currentMedia.stop).toHaveBeenCalled()
    for (const uploader of harness.uploaders) {
      expect(uploader.abort).not.toHaveBeenCalled()
    }
  })

  it('times out waiting for session.ended without getting stuck', async () => {
    const { wrapper, router } = await mountPage()

    const options = harness.state.options as {
      onStatus: (status: unknown) => void
      onEvent: (event: ServerEvent) => void
    }
    options.onStatus({ status: 'connected' })
    options.onEvent(snapshotEvent('in_progress', 8))
    await flushPromises()

    vi.useFakeTimers()
    try {
      await wrapper.get('[data-testid="end-session"]').trigger('click')
      await vi.advanceTimersByTimeAsync(10_000)
      await nextTick()
    } finally {
      vi.useRealTimers()
    }

    expect(wrapper.text()).toContain('结束试讲超时')
    expect(router.currentRoute.value.path).toBe(
      '/student/sessions/session-1/classroom',
    )
    expect(wrapper.find('[data-testid="classroom-ending"]').exists()).toBe(false)
    expect(
      wrapper.get('[data-testid="end-session"]').attributes('disabled'),
    ).toBeUndefined()
    expect(currentMedia.stop).toHaveBeenCalled()
    for (const uploader of harness.uploaders) {
      expect(uploader.abort).not.toHaveBeenCalled()
    }
  })

  it('keeps the page when an upload fails during the end flow', async () => {
    const { wrapper, router } = await mountPage()

    const options = harness.state.options as {
      onStatus: (status: unknown) => void
      onEvent: (event: ServerEvent) => void
    }
    options.onStatus({ status: 'connected' })
    options.onEvent(snapshotEvent('in_progress', 9))
    await flushPromises()

    harness.uploaders[0]!.stop.mockRejectedValue(new Error('分片上传失败'))
    await wrapper.get('[data-testid="end-session"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('分片上传失败')
    expect(router.currentRoute.value.path).toBe(
      '/student/sessions/session-1/classroom',
    )
    expect(wrapper.find('[data-testid="classroom-ending"]').exists()).toBe(false)
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

    const options = harness.state.options as {
      recordingState: () => 'active' | 'lost'
      onStatus: (status: unknown) => void
      onEvent: (event: ServerEvent) => void
    }
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

    const options = harness.state.options as {
      onEvent: (event: ServerEvent) => void
    }
    options.onEvent(snapshotEvent('in_progress', 7))
    await flushPromises()

    const uploader = harness.uploaders[0]!
    uploader.options.onError?.(new Error('媒体轨道已结束，录制失败'))
    await flushPromises()

    expect(wrapper.text()).toContain('媒体轨道已结束')
    expect(
      wrapper.get('[data-testid="end-session"]').attributes('disabled'),
    ).toBeDefined()

    for (const item of harness.uploaders) {
      expect(item.abort).toHaveBeenCalled()
    }
    expect(harness.fakeWs.close).toHaveBeenCalled()
    expect(currentMedia.stop).toHaveBeenCalled()
    expect(harness.fakeWs.send).not.toHaveBeenCalledWith(
      'session.failed',
      expect.anything(),
    )
  })

  it('waits for the start click before acquiring media, then runs the ready/start flow', async () => {
    const { wrapper } = await mountPage({ autoStart: false })

    expect(getSession).toHaveBeenCalledWith('session-1')
    expect(acquireMedia).not.toHaveBeenCalled()
    expect(createRecorder).not.toHaveBeenCalled()
    expect(harness.uploaders).toHaveLength(0)
    expect(harness.state.options).toBeNull()

    const gate = wrapper.get('[data-testid="classroom-gate"]')
    expect(gate.text()).toContain('摄像头')
    expect(gate.text()).toContain('屏幕共享')
    expect(gate.text()).toContain('麦克风')
    expect(wrapper.find('[data-testid="end-session"]').exists()).toBe(false)

    await wrapper.get('[data-testid="start-recording"]').trigger('click')
    await flushPromises()

    expect(acquireMedia).toHaveBeenCalledWith('free')
    expect(createRecorder).toHaveBeenCalledTimes(3)
    expect(harness.uploaders.map((uploader) => uploader.streamType)).toEqual([
      'camera',
      'screen',
      'audio',
    ])
    expect(harness.state.options).toBeTruthy()
    expect(wrapper.find('[data-testid="classroom-gate"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="end-session"]').exists()).toBe(true)

    const options = harness.state.options as {
      onEvent: (event: ServerEvent) => void
    }
    options.onEvent(snapshotEvent('preparing'))
    await flushPromises()
    expect(harness.fakeWs.send).toHaveBeenCalledWith('recording.ready', {
      streams: [
        { stream_type: 'camera', status: 'ready' },
        { stream_type: 'screen', status: 'ready' },
        { stream_type: 'audio', status: 'ready' },
      ],
    })

    options.onEvent(
      serverEvent('recording.ready_ack', {
        required_streams: ['camera', 'screen', 'audio'],
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
    vi.mocked(acquireMedia).mockRejectedValue(new Error('用户拒绝了屏幕共享'))

    const { wrapper } = await mountPage()

    expect(cancelSession).toHaveBeenCalledWith('session-1')
    expect(wrapper.get('[data-testid="classroom-failure"]').text()).toContain(
      '用户拒绝了屏幕共享',
    )
    expect(harness.state.options).toBeNull()
  })

  it('cancels a preparing session when recorder creation fails', async () => {
    vi.mocked(createRecorder).mockImplementationOnce(() => {
      throw new Error('录制器创建失败')
    })

    const { wrapper } = await mountPage()

    expect(cancelSession).toHaveBeenCalledWith('session-1')
    expect(wrapper.get('[data-testid="classroom-failure"]').text()).toContain(
      '录制器创建失败',
    )
  })

  it('cancels a preparing session when the control channel cannot connect', async () => {
    const { wrapper } = await mountPage()

    const options = harness.state.options as {
      onStatus: (status: unknown) => void
    }
    options.onStatus({ status: 'failed', reason: 'connect_failed' })
    await flushPromises()

    expect(cancelSession).toHaveBeenCalledWith('session-1')
    expect(wrapper.get('[data-testid="classroom-failure"]').text()).toContain(
      '无法连接课堂服务',
    )
  })

  it('cancels a preparing session when leaving the start gate', async () => {
    const { wrapper } = await mountPage({ autoStart: false })

    expect(acquireMedia).not.toHaveBeenCalled()
    wrapper.unmount()
    await flushPromises()

    expect(cancelSession).toHaveBeenCalledWith('session-1')
  })

  it('cancels a preparing session when unmounted before the load finishes', async () => {
    let resolveSession!: (value: SessionView) => void
    const pending = new Promise<SessionView>((resolve) => {
      resolveSession = resolve
    })
    vi.mocked(getSession).mockReset().mockReturnValue(pending)

    const { wrapper } = await mountPage({ autoStart: false })
    wrapper.unmount()
    resolveSession(sessionView())
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

      const options = harness.state.options as {
        onStatus: (status: unknown) => void
        onEvent: (event: ServerEvent) => void
      }
      options.onStatus({ status: 'connected' })
      options.onEvent(snapshotEvent('in_progress', 4))
      await flushPromises()

      await wrapper.get('[data-testid="end-session"]').trigger('click')
      await flushPromises()

      expect(removeSpy.mock.calls.some(([type]) => type === 'beforeunload')).toBe(true)

      options.onEvent(serverEvent('session.ended', { reason: 'manual', duration_ms: 1_000 }))
      await flushPromises()
      wrapper.unmount()
    } finally {
      addSpy.mockRestore()
      removeSpy.mockRestore()
    }
  })
})