import { describe, expect, it, vi } from 'vitest'
import {
  MediaError,
  acquireMedia,
  isSupportedBrowser,
  type AcquireMediaDeps,
  type MediaDevicesLike,
} from './media'

const CHROME_UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
const EDGE_UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0'
const FIREFOX_UA =
  'Mozilla/5.0 (X11; Linux x86_64; rv:121.0) Gecko/20100101 Firefox/121.0'

class FakeTrack {
  readonly kind: 'video' | 'audio'
  readyState = 'live'
  readonly stop = vi.fn(() => {
    this.readyState = 'ended'
  })

  constructor(kind: 'video' | 'audio') {
    this.kind = kind
  }
}

function fakeStream(tracks: FakeTrack[]): MediaStream {
  return {
    getTracks: () => tracks,
    getVideoTracks: () => tracks.filter((track) => track.kind === 'video'),
    getAudioTracks: () => tracks.filter((track) => track.kind === 'audio'),
  } as unknown as MediaStream
}

function createDeps(options: {
  userAgent?: string
  getUserMedia: (constraints: MediaStreamConstraints) => Promise<MediaStream>
  getDisplayMedia?: (constraints: DisplayMediaStreamOptions) => Promise<MediaStream>
}): AcquireMediaDeps {
  return {
    userAgent: options.userAgent ?? CHROME_UA,
    mediaDevices: {
      getUserMedia: options.getUserMedia,
      getDisplayMedia: options.getDisplayMedia,
    } as MediaDevicesLike,
    createStream: (tracks) => fakeStream(tracks as unknown as FakeTrack[]),
  }
}

function combinedStream(): {
  stream: MediaStream
  video: FakeTrack
  audio: FakeTrack
} {
  const video = new FakeTrack('video')
  const audio = new FakeTrack('audio')
  return { stream: fakeStream([video, audio]), video, audio }
}

function mediaError(name: string): Error {
  return Object.assign(new Error(name), { name })
}

describe('isSupportedBrowser', () => {
  it('accepts Chrome and Edge only', () => {
    expect(isSupportedBrowser(CHROME_UA)).toBe(true)
    expect(isSupportedBrowser(EDGE_UA)).toBe(true)
    expect(isSupportedBrowser(FIREFOX_UA)).toBe(false)
    expect(
      isSupportedBrowser(
        'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15',
      ),
    ).toBe(false)
  })
})

describe('acquireMedia', () => {
  it('rejects unsupported browsers before touching media devices', async () => {
    const getUserMedia = vi.fn()
    const getDisplayMedia = vi.fn()

    await expect(
      acquireMedia(
        'free',
        createDeps({ userAgent: FIREFOX_UA, getUserMedia, getDisplayMedia }),
      ),
    ).rejects.toMatchObject({ name: 'MediaError', reason: 'unsupported' })

    expect(getUserMedia).not.toHaveBeenCalled()
    expect(getDisplayMedia).not.toHaveBeenCalled()
  })

  it('propagates permission denial as MediaError(permission)', async () => {
    const getUserMedia = vi.fn(async () => {
      throw mediaError('NotAllowedError')
    })

    const promise = acquireMedia(
      'free',
      createDeps({ getUserMedia, getDisplayMedia: vi.fn() }),
    )

    await expect(promise).rejects.toBeInstanceOf(MediaError)
    await expect(promise).rejects.toMatchObject({ reason: 'permission' })
  })

  it('maps missing devices to MediaError(missing)', async () => {
    const getUserMedia = vi.fn(async () => {
      throw mediaError('NotFoundError')
    })

    await expect(
      acquireMedia('free', createDeps({ getUserMedia, getDisplayMedia: vi.fn() })),
    ).rejects.toMatchObject({ name: 'MediaError', reason: 'missing' })
  })

  it('does not request screen for interview mode', async () => {
    const { stream, video, audio } = combinedStream()
    const getUserMedia = vi.fn(async () => stream)
    const getDisplayMedia = vi.fn(async () => fakeStream([new FakeTrack('video')]))

    const media = await acquireMedia(
      'interview',
      createDeps({ getUserMedia, getDisplayMedia }),
    )

    expect(getUserMedia).toHaveBeenCalledTimes(1)
    expect(getUserMedia).toHaveBeenCalledWith({ video: true, audio: true })
    expect(getDisplayMedia).not.toHaveBeenCalled()
    expect(media.screen).toBeUndefined()
    expect(media.camera.getVideoTracks()).toEqual([video])
    expect(media.audio.getAudioTracks()).toEqual([audio])

    media.stop()
    expect(video.stop).toHaveBeenCalledTimes(1)
    expect(audio.stop).toHaveBeenCalledTimes(1)
  })

  it('requests a screen stream for free and scenario modes and stops every track', async () => {
    for (const mode of ['free', 'scenario'] as const) {
      const { stream, video, audio } = combinedStream()
      const screenTrack = new FakeTrack('video')
      const screen = fakeStream([screenTrack])
      const getUserMedia = vi.fn(async () => stream)
      const getDisplayMedia = vi.fn(async () => screen)

      const media = await acquireMedia(
        mode,
        createDeps({ getUserMedia, getDisplayMedia }),
      )

      expect(getDisplayMedia).toHaveBeenCalledWith({ video: true })
      expect(media.screen).toBe(screen)

      media.stop()
      expect(video.stop).toHaveBeenCalledTimes(1)
      expect(audio.stop).toHaveBeenCalledTimes(1)
      expect(screenTrack.stop).toHaveBeenCalledTimes(1)
    }
  })

  it('stops camera and microphone tracks when screen sharing is denied', async () => {
    const { stream, video, audio } = combinedStream()
    const getUserMedia = vi.fn(async () => stream)
    const getDisplayMedia = vi.fn(async () => {
      throw mediaError('NotAllowedError')
    })

    await expect(
      acquireMedia('free', createDeps({ getUserMedia, getDisplayMedia })),
    ).rejects.toMatchObject({ name: 'MediaError', reason: 'permission' })

    expect(video.stop).toHaveBeenCalledTimes(1)
    expect(audio.stop).toHaveBeenCalledTimes(1)
  })
})