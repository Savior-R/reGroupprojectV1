import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { routes } from '../../app/router'
import ClassroomPage from './ClassroomPage.vue'
import { getSession } from '../../api/sessions'
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

async function mountPage() {
  const router = createRouter({ history: createMemoryHistory(), routes })
  await router.push('/student/sessions/session-1/classroom')
  await router.isReady()
  const wrapper = mount(ClassroomPage, { global: { plugins: [router] } })
  await flushPromises()
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
  vi.mocked(acquireMedia).mockReset().mockResolvedValue(currentMedia)
  vi.mocked(createRecorder)
    .mockReset()
    .mockReturnValue({ state: 'recording', stop: vi.fn() } as unknown as MediaRecorder)
})

describe('ClassroomPage', () => {
  it('sends recording.ready then session.start and renders the snapshot', async () => {
    const { wrapper } = await mountPage()

    expect(getSession).toHaveBeenCalledWith('session-1')
    expect(acquireMedia).toHaveBeenCalledWith('free')
    expect(harness.uploaders.map((uploader) => uploader.streamType)).toEqual([
      'camera',
      'screen',
      'audio',
    ])
    for (const uploader of harness.uploaders) {
      expect(uploader.start).toHaveBeenCalledTimes(1)
    }
    expect(harness.state.options).toBeTruthy()

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

    expect(harness.fakeWs.send).toHaveBeenCalledWith('session.start', {})
  })

  it('ends the session and stops recorders before routing to review', async () => {
    const { wrapper, router } = await mountPage()

    const options = harness.state.options as {
      onEvent: (event: ServerEvent) => void
    }
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

  it('reports a lost recording when the page loads an in-progress session', async () => {
    vi.mocked(getSession)
      .mockReset()
      .mockResolvedValue(sessionView({ status: 'in_progress' }))

    const { wrapper } = await mountPage()

    const options = harness.state.options as {
      onStatus: (status: unknown) => void
      onEvent: (event: ServerEvent) => void
    }
    options.onStatus({ status: 'connected' })

    expect(harness.fakeWs.resume).toHaveBeenCalledTimes(1)
    expect(harness.fakeWs.send).not.toHaveBeenCalledWith('session.start', {})

    options.onEvent(
      serverEvent('session.failed', {
        reason: 'recording_lost',
        error_code: 'SESSION_FAILED',
      }),
    )
    await flushPromises()

    expect(wrapper.text()).toContain('会话失败')
    expect(currentMedia.stop).toHaveBeenCalled()
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
})