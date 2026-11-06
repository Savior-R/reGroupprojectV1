<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import EventTimeline from './EventTimeline.vue'
import ReplayControls from './ReplayControls.vue'
import type { ReplayEvent } from '../../api/replay'
import type { ReplayStreamView, ReplayView, StreamType } from '../../types/recording'
import { clampSeek, needsCorrection, seekAll, targetTime } from '../../utils/replaySync'
import type { ReplaySyncPlayer } from '../../utils/replaySync'

/**
 * Synchronized multi-stream replay player (05B §7.2-§7.3, 09 §10.4).
 *
 * Audio is the master clock (both recorded modes require it). Every 500 ms the
 * player compares each silent video's local position with the position derived
 * from the audio and the manifest offset; a drift above 200 ms triggers a
 * correction. Free/scenario mode renders screen main + camera thumbnail;
 * interview mode renders camera main.
 *
 * The manifest contains short-lived presigned URLs. On a media error or after
 * 14 minutes the player emits `refresh` so the parent can refetch the manifest.
 * Refreshes are rate-limited and bounded: a successful reload does not reset
 * the retry budget until every stream has loaded and stayed healthy for a
 * sustained window, so one permanently broken stream cannot cause an infinite
 * reload loop.
 */

const props = withDefaults(
  defineProps<{
    replay: ReplayView
    events?: ReplayEvent[]
    initialTimeMs?: number
  }>(),
  {
    events: () => [],
    initialTimeMs: 0,
  },
)

const emit = defineEmits<{
  refresh: []
}>()

const SYNC_INTERVAL_MS = 500
const DRIFT_THRESHOLD_MS = 200
const MAX_REFRESH_ATTEMPTS = 2
const PRESIGNED_REFRESH_MS = 14 * 60 * 1000
/** Never reload the manifest more than once per 2 s. */
const MIN_REFRESH_INTERVAL_MS = 2_000
/** Reset the retry budget only after every stream stayed healthy this long. */
const HEALTHY_WINDOW_MS = 30_000

const isInterview = computed(() => props.replay.mode === 'interview')

const streamsByType = computed(() => {
  const map = new Map<StreamType, ReplayStreamView>()
  for (const stream of props.replay.streams) {
    map.set(stream.stream_type, stream)
  }
  return map
})

const audioStream = computed(() => streamsByType.value.get('audio') ?? null)
const cameraStream = computed(() => streamsByType.value.get('camera') ?? null)
const screenStream = computed(() => streamsByType.value.get('screen') ?? null)
const firstStream = computed(() => props.replay.streams[0] ?? null)

/** Audio is the master clock; fall back to the first stream if absent. */
const masterType = computed<StreamType | null>(() =>
  audioStream.value ? 'audio' : (firstStream.value?.stream_type ?? null),
)
const masterStream = computed(() =>
  masterType.value ? (streamsByType.value.get(masterType.value) ?? null) : null,
)

const stage = ref<HTMLElement | null>(null)
const screenEl = ref<HTMLVideoElement | null>(null)
const cameraEl = ref<HTMLVideoElement | null>(null)
const audioEl = ref<HTMLAudioElement | null>(null)

const playing = ref(false)
const currentTimeMs = ref(0)
const rate = ref(1)
const volume = ref(1)
const refreshExhausted = ref(false)

const durationMs = computed(() => {
  const duration = props.replay.duration_ms
  return Number.isFinite(duration) && duration > 0 ? duration : 0
})

let syncTimer: ReturnType<typeof setInterval> | null = null
let refreshTimer: ReturnType<typeof setTimeout> | null = null
let backoffTimer: ReturnType<typeof setTimeout> | null = null
let healthyTimer: ReturnType<typeof setTimeout> | null = null
let refreshAttempts = 0
let lastRefreshAt = 0
let resumeMs: number | null = null
let initialApplied = false
let disposed = false

/**
 * Per-manifest health tracking. The retry budget is only reset after every
 * stream of the *current* manifest has loaded and no stream errored during the
 * healthy window; one failing stream therefore cannot reset it via its healthy
 * siblings' `loadedmetadata` events.
 */
let loadedStreams = new Set<StreamType>()
let erroredStreams = new Set<StreamType>()

function mediaElement(type: StreamType): HTMLMediaElement | null {
  if (type === 'screen') {
    return screenEl.value
  }
  if (type === 'camera') {
    return cameraEl.value
  }
  return audioEl.value
}

function mediaEntries(): Array<[StreamType, HTMLMediaElement]> {
  const entries: Array<[StreamType, HTMLMediaElement]> = []
  for (const type of ['screen', 'camera', 'audio'] as const) {
    const element = mediaElement(type)
    if (element) {
      entries.push([type, element])
    }
  }
  return entries
}

function masterElement(): HTMLMediaElement | null {
  return masterType.value ? mediaElement(masterType.value) : null
}

function mediaDuration(element: HTMLMediaElement): number {
  return Number.isFinite(element.duration) ? element.duration : Number.POSITIVE_INFINITY
}

function currentSessionMs(): number {
  const master = masterElement()
  if (!master) {
    return currentTimeMs.value
  }
  const localMs = Number.isFinite(master.currentTime) ? master.currentTime * 1000 : 0
  return localMs + (masterStream.value?.offset_ms ?? 0)
}

function targetOf(stream: ReplayStreamView): ReplaySyncPlayer | null {
  const element = mediaElement(stream.stream_type)
  if (!element) {
    return null
  }
  return {
    get currentTime(): number {
      return element.currentTime
    },
    set currentTime(value: number) {
      element.currentTime = value
    },
    offsetMs: stream.offset_ms,
    duration: mediaDuration(element),
  }
}

function startSync(): void {
  if (syncTimer !== null || disposed || !playing.value) {
    return
  }
  syncTimer = setInterval(syncOnce, SYNC_INTERVAL_MS)
}

function stopSync(): void {
  if (syncTimer !== null) {
    clearInterval(syncTimer)
    syncTimer = null
  }
}

function syncOnce(): void {
  if (!playing.value) {
    return
  }
  const master = masterElement()
  if (!master) {
    return
  }
  const sessionMs = currentSessionMs()
  currentTimeMs.value = sessionMs
  for (const stream of streamsByType.value.values()) {
    if (stream.stream_type === masterType.value) {
      continue
    }
    const element = mediaElement(stream.stream_type)
    if (!element) {
      continue
    }
    const expected = targetTime(sessionMs, stream.offset_ms)
    if (needsCorrection(expected, element.currentTime, DRIFT_THRESHOLD_MS)) {
      element.currentTime = clampSeek(expected, mediaDuration(element))
    }
  }
}

function seekToSessionMs(sessionMs: number): void {
  const totalSeconds =
    durationMs.value > 0 ? durationMs.value / 1000 : Number.POSITIVE_INFINITY
  const boundedMs = clampSeek(sessionMs / 1000, totalSeconds) * 1000
  currentTimeMs.value = boundedMs
  const targets = [...streamsByType.value.values()]
    .map(targetOf)
    .filter((target): target is ReplaySyncPlayer => target !== null)
  seekAll(targets, boundedMs)
}

/** Start (or restart) every element from the master's current position. */
async function playAll(): Promise<void> {
  const master = masterElement()
  if (!master) {
    return
  }
  master.playbackRate = rate.value
  try {
    await master.play()
  } catch {
    playing.value = false
    stopSync()
    return
  }
  // `play()` may resolve after the user paused or the component unmounted;
  // never leave a sync interval running for a stopped player.
  if (disposed || !playing.value) {
    stopSync()
    return
  }
  startSync()
  for (const [type, element] of mediaEntries()) {
    if (type === masterType.value) {
      continue
    }
    element.playbackRate = rate.value
    try {
      await element.play()
    } catch {
      // Muted followers may be blocked by autoplay policy; the sync loop
      // still seeks them and a later play/pause retries.
    }
  }
}

async function play(): Promise<void> {
  if (!masterElement()) {
    return
  }
  playing.value = true
  await playAll()
}

function pause(): void {
  playing.value = false
  stopSync()
  for (const [, element] of mediaEntries()) {
    try {
      element.pause()
    } catch {
      // jsdom / embedded browsers may not implement pause.
    }
  }
}

function togglePlay(): void {
  if (playing.value) {
    pause()
  } else {
    void play()
  }
}

function setRate(value: number): void {
  rate.value = value
  for (const [, element] of mediaEntries()) {
    element.playbackRate = value
  }
}

function setVolume(value: number): void {
  volume.value = value
  const audio = mediaElement('audio')
  if (audio) {
    audio.volume = value
  }
}

async function enterFullscreen(): Promise<void> {
  const element = stage.value
  if (!element) {
    return
  }
  if (document.fullscreenElement) {
    try {
      await document.exitFullscreen()
    } catch {
      // Ignore a rejected fullscreen exit.
    }
    return
  }
  try {
    await element.requestFullscreen()
  } catch {
    // Fullscreen can be denied; playback continues inline.
  }
}

function applyInitialSeek(): void {
  if (initialApplied || props.initialTimeMs <= 0) {
    return
  }
  initialApplied = true
  seekToSessionMs(props.initialTimeMs)
}

function applyPendingResume(): void {
  if (resumeMs !== null) {
    seekToSessionMs(resumeMs)
  }
}

function resumeIfPlaying(): void {
  if (playing.value) {
    void playAll()
  }
}

function allStreamsLoaded(): boolean {
  const types = [...streamsByType.value.keys()]
  return types.length > 0 && types.every((type) => loadedStreams.has(type))
}

function clearHealthyTimer(): void {
  if (healthyTimer !== null) {
    clearTimeout(healthyTimer)
    healthyTimer = null
  }
}

function clearBackoffTimer(): void {
  if (backoffTimer !== null) {
    clearTimeout(backoffTimer)
    backoffTimer = null
  }
}

function resetHealthTracking(): void {
  loadedStreams = new Set()
  erroredStreams = new Set()
  clearHealthyTimer()
}

/**
 * Only a fully loaded, error-free manifest that stays healthy for a sustained
 * window restores the retry budget. Merely receiving `loadedmetadata` from the
 * healthy streams must not reset it while a sibling keeps failing.
 */
function noteHealthy(): void {
  if (erroredStreams.size > 0 || !allStreamsLoaded()) {
    clearHealthyTimer()
    return
  }
  if (healthyTimer !== null) {
    return
  }
  healthyTimer = setTimeout(() => {
    healthyTimer = null
    if (erroredStreams.size === 0 && allStreamsLoaded()) {
      refreshAttempts = 0
    }
  }, HEALTHY_WINDOW_MS)
}

function onMediaLoaded(type: StreamType): void {
  loadedStreams.add(type)
  applyPendingResume()
  if (allStreamsLoaded()) {
    resumeMs = null
  }
  noteHealthy()
  // A manifest reload replaces the media sources; the UI stays in the playing
  // state, so playback must be restarted once the new elements have loaded.
  resumeIfPlaying()
}

function onMediaError(type: StreamType): void {
  erroredStreams.add(type)
  clearHealthyTimer()
  requestRefresh()
}

function onEnded(type: StreamType): void {
  if (type !== masterType.value) {
    return
  }
  pause()
  if (durationMs.value > 0) {
    currentTimeMs.value = durationMs.value
  }
}

function scheduleExpiryRefresh(): void {
  if (refreshTimer !== null) {
    clearTimeout(refreshTimer)
  }
  refreshTimer = setTimeout(() => {
    refreshTimer = null
    requestRefresh()
  }, PRESIGNED_REFRESH_MS)
}

function requestRefresh(): void {
  if (refreshExhausted.value) {
    return
  }
  if (refreshAttempts >= MAX_REFRESH_ATTEMPTS) {
    refreshExhausted.value = true
    clearBackoffTimer()
    return
  }
  const elapsed = Date.now() - lastRefreshAt
  if (elapsed < MIN_REFRESH_INTERVAL_MS) {
    if (backoffTimer === null) {
      backoffTimer = setTimeout(() => {
        backoffTimer = null
        requestRefresh()
      }, MIN_REFRESH_INTERVAL_MS - elapsed)
    }
    return
  }
  refreshAttempts += 1
  lastRefreshAt = Date.now()
  resumeMs = currentSessionMs()
  emit('refresh')
  scheduleExpiryRefresh()
}

/** Capture the current position before the parent fetches a fresh manifest. */
function beginReload(): void {
  resumeMs = currentSessionMs()
}

defineExpose({ beginReload })

watch(
  () => props.replay,
  async () => {
    resetHealthTracking()
    clearBackoffTimer()
    refreshExhausted.value = false
    scheduleExpiryRefresh()
    // Same-URL reloads may not remount the elements; restore the position and
    // playback as soon as Vue has applied the new sources.
    await nextTick()
    applyPendingResume()
    resumeIfPlaying()
  },
)

onMounted(() => {
  void nextTick(applyInitialSeek)
  scheduleExpiryRefresh()
})

onBeforeUnmount(() => {
  disposed = true
  stopSync()
  clearHealthyTimer()
  clearBackoffTimer()
  if (refreshTimer !== null) {
    clearTimeout(refreshTimer)
    refreshTimer = null
  }
  pause()
})
</script>

<template>
  <div class="replay-player" data-testid="replay-player">
    <div
      ref="stage"
      class="replay-player__stage"
      :class="{ 'replay-player__stage--interview': isInterview }"
    >
      <template v-if="isInterview">
        <video
          v-if="cameraStream"
          :key="`camera-${cameraStream.url}`"
          ref="cameraEl"
          class="replay-player__media replay-player__media--main"
          :src="cameraStream.url"
          muted
          playsinline
          preload="auto"
          data-testid="replay-camera"
          @loadedmetadata="onMediaLoaded('camera')"
          @error="onMediaError('camera')"
          @ended="onEnded('camera')"
        />
        <p v-else class="replay-player__missing" data-testid="replay-missing-camera">
          摄像头录像不可用
        </p>
      </template>

      <template v-else>
        <video
          v-if="screenStream"
          :key="`screen-${screenStream.url}`"
          ref="screenEl"
          class="replay-player__media replay-player__media--main"
          :src="screenStream.url"
          muted
          playsinline
          preload="auto"
          data-testid="replay-screen"
          @loadedmetadata="onMediaLoaded('screen')"
          @error="onMediaError('screen')"
          @ended="onEnded('screen')"
        />
        <p v-else class="replay-player__missing" data-testid="replay-missing-screen">
          屏幕录像不可用
        </p>

        <video
          v-if="cameraStream"
          :key="`camera-${cameraStream.url}`"
          ref="cameraEl"
          class="replay-player__media replay-player__media--thumb"
          :src="cameraStream.url"
          muted
          playsinline
          preload="auto"
          data-testid="replay-camera"
          @loadedmetadata="onMediaLoaded('camera')"
          @error="onMediaError('camera')"
          @ended="onEnded('camera')"
        />
      </template>

      <audio
        v-if="audioStream"
        :key="`audio-${audioStream.url}`"
        ref="audioEl"
        class="replay-player__audio"
        :src="audioStream.url"
        preload="auto"
        data-testid="replay-audio"
        @loadedmetadata="onMediaLoaded('audio')"
        @error="onMediaError('audio')"
        @ended="onEnded('audio')"
      />

      <p v-if="refreshExhausted" class="replay-player__notice" data-testid="replay-expired">
        回放地址已过期，请刷新页面后重试。
      </p>
    </div>

    <ReplayControls
      :playing="playing"
      :current-time-ms="currentTimeMs"
      :duration-ms="durationMs"
      :rate="rate"
      :volume="volume"
      @toggle="togglePlay"
      @seek="seekToSessionMs"
      @rate="setRate"
      @volume="setVolume"
      @fullscreen="enterFullscreen"
    />

    <EventTimeline :events="events" @seek="seekToSessionMs" />
  </div>
</template>

<style scoped>
.replay-player {
  display: flex;
  min-height: 0;
  flex: 1;
  flex-direction: column;
  gap: 10px;
}

.replay-player__stage {
  position: relative;
  display: flex;
  min-height: 240px;
  flex: 1;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border: 1px solid rgba(148, 163, 184, 0.22);
  border-radius: 12px;
  background: #020617;
}

.replay-player__media--main {
  width: 100%;
  height: 100%;
  max-height: 100%;
  object-fit: contain;
  background: #020617;
}

.replay-player__media--thumb {
  position: absolute;
  right: 16px;
  bottom: 16px;
  width: 220px;
  aspect-ratio: 4 / 3;
  border: 1px solid rgba(148, 163, 184, 0.4);
  border-radius: 10px;
  background: #020617;
  box-shadow: 0 12px 30px rgba(0, 0, 0, 0.45);
  object-fit: cover;
}

.replay-player__audio {
  display: none;
}

.replay-player__missing {
  margin: 0;
  color: #94a3b8;
}

.replay-player__notice {
  position: absolute;
  left: 50%;
  bottom: 16px;
  margin: 0;
  padding: 6px 14px;
  transform: translateX(-50%);
  border-radius: 999px;
  background: rgba(127, 29, 29, 0.9);
  color: #fecaca;
  font-size: 13px;
}
</style>