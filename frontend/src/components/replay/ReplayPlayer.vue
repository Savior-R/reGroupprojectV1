<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { ComponentPublicInstance } from 'vue'
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
 * 14 minutes the player emits `refresh` so the parent can refetch the manifest;
 * consecutive failures are bounded to avoid an infinite reload loop.
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
const mediaEls = new Map<StreamType, HTMLMediaElement>()

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
let refreshAttempts = 0
let resumeMs: number | null = null
let initialApplied = false

function setMediaRef(
  type: StreamType,
  value: Element | ComponentPublicInstance | null,
): void {
  if (value instanceof HTMLMediaElement) {
    mediaEls.set(type, value)
  } else {
    mediaEls.delete(type)
  }
}

function masterElement(): HTMLMediaElement | null {
  return masterType.value ? (mediaEls.get(masterType.value) ?? null) : null
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
  const element = mediaEls.get(stream.stream_type)
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
  if (syncTimer !== null) {
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
    const element = mediaEls.get(stream.stream_type)
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

async function play(): Promise<void> {
  const master = masterElement()
  if (!master) {
    return
  }
  master.playbackRate = rate.value
  try {
    await master.play()
  } catch {
    playing.value = false
    return
  }
  playing.value = true
  startSync()
  for (const [type, element] of mediaEls) {
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

function pause(): void {
  playing.value = false
  stopSync()
  for (const element of mediaEls.values()) {
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
  for (const element of mediaEls.values()) {
    element.playbackRate = value
  }
}

function setVolume(value: number): void {
  volume.value = value
  const audio = mediaEls.get('audio')
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

function onMediaLoaded(): void {
  refreshAttempts = 0
  if (resumeMs !== null) {
    const target = resumeMs
    resumeMs = null
    seekToSessionMs(target)
    return
  }
  applyInitialSeek()
}

function onMediaError(): void {
  requestRefresh()
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
    return
  }
  refreshAttempts += 1
  resumeMs = currentSessionMs()
  emit('refresh')
  scheduleExpiryRefresh()
}

watch(
  () => props.replay,
  () => {
    refreshExhausted.value = false
    scheduleExpiryRefresh()
  },
)

onMounted(() => {
  void nextTick(applyInitialSeek)
  scheduleExpiryRefresh()
})

onBeforeUnmount(() => {
  stopSync()
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
          :ref="(element) => setMediaRef('camera', element)"
          class="replay-player__media replay-player__media--main"
          :src="cameraStream.url"
          muted
          playsinline
          preload="auto"
          data-testid="replay-camera"
          @loadedmetadata="onMediaLoaded"
          @error="onMediaError"
        />
        <p v-else class="replay-player__missing" data-testid="replay-missing-camera">
          摄像头录像不可用
        </p>
      </template>

      <template v-else>
        <video
          v-if="screenStream"
          :key="`screen-${screenStream.url}`"
          :ref="(element) => setMediaRef('screen', element)"
          class="replay-player__media replay-player__media--main"
          :src="screenStream.url"
          muted
          playsinline
          preload="auto"
          data-testid="replay-screen"
          @loadedmetadata="onMediaLoaded"
          @error="onMediaError"
        />
        <p v-else class="replay-player__missing" data-testid="replay-missing-screen">
          屏幕录像不可用
        </p>

        <video
          v-if="cameraStream"
          :key="`camera-${cameraStream.url}`"
          :ref="(element) => setMediaRef('camera', element)"
          class="replay-player__media replay-player__media--thumb"
          :src="cameraStream.url"
          muted
          playsinline
          preload="auto"
          data-testid="replay-camera"
          @loadedmetadata="onMediaLoaded"
          @error="onMediaError"
        />
      </template>

      <audio
        v-if="audioStream"
        :key="`audio-${audioStream.url}`"
        :ref="(element) => setMediaRef('audio', element)"
        class="replay-player__audio"
        :src="audioStream.url"
        preload="auto"
        data-testid="replay-audio"
        @loadedmetadata="onMediaLoaded"
        @error="onMediaError"
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