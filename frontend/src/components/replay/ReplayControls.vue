<script setup lang="ts">
import { formatClock } from '../../utils/replayFormat'

/**
 * Replay transport controls (05B §7.3, 09 §10.4).
 *
 * Presentational only: the player owns the media elements and turns these
 * events into play / pause / seek / rate / volume / fullscreen actions.
 */

withDefaults(
  defineProps<{
    playing: boolean
    currentTimeMs: number
    durationMs: number
    rate?: number
    volume?: number
  }>(),
  {
    rate: 1,
    volume: 1,
  },
)

const emit = defineEmits<{
  toggle: []
  seek: [timeMs: number]
  rate: [value: number]
  volume: [value: number]
  fullscreen: []
}>()

const RATES = [0.5, 1, 1.5, 2] as const

function onSeek(event: Event): void {
  emit('seek', Number((event.target as HTMLInputElement).value))
}

function onRate(event: Event): void {
  emit('rate', Number((event.target as HTMLSelectElement).value))
}

function onVolume(event: Event): void {
  emit('volume', Number((event.target as HTMLInputElement).value))
}
</script>

<template>
  <div class="replay-controls" role="toolbar" aria-label="回放控制">
    <button
      type="button"
      class="replay-controls__button replay-controls__button--play"
      :aria-label="playing ? '暂停' : '播放'"
      data-testid="replay-toggle"
      @click="emit('toggle')"
    >
      {{ playing ? '暂停' : '播放' }}
    </button>

    <span class="replay-controls__time" data-testid="replay-time">
      {{ formatClock(currentTimeMs) }} / {{ formatClock(durationMs) }}
    </span>

    <input
      class="replay-controls__seek"
      type="range"
      min="0"
      :max="Math.max(durationMs, 0)"
      step="100"
      :value="Math.min(currentTimeMs, Math.max(durationMs, 0))"
      aria-label="播放进度"
      data-testid="replay-seek"
      @input="onSeek"
    />

    <label class="replay-controls__field">
      <span class="replay-controls__label">倍速</span>
      <select
        class="replay-controls__select"
        :value="String(rate)"
        aria-label="播放倍速"
        data-testid="replay-rate"
        @change="onRate"
      >
        <option v-for="value in RATES" :key="value" :value="String(value)">
          {{ value }}×
        </option>
      </select>
    </label>

    <label class="replay-controls__field">
      <span class="replay-controls__label">音量</span>
      <input
        class="replay-controls__volume"
        type="range"
        min="0"
        max="1"
        step="0.05"
        :value="volume"
        aria-label="音量"
        data-testid="replay-volume"
        @input="onVolume"
      />
    </label>

    <button
      type="button"
      class="replay-controls__button"
      data-testid="replay-fullscreen"
      @click="emit('fullscreen')"
    >
      全屏
    </button>
  </div>
</template>

<style scoped>
.replay-controls {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border: 1px solid rgba(148, 163, 184, 0.22);
  border-radius: 12px;
  background: rgba(15, 23, 42, 0.86);
}

.replay-controls__button {
  padding: 5px 12px;
  border: 1px solid rgba(148, 163, 184, 0.32);
  border-radius: 8px;
  background: rgba(148, 163, 184, 0.12);
  color: #e5e7eb;
  font-size: 13px;
  cursor: pointer;
}

.replay-controls__button:hover {
  border-color: rgba(96, 165, 250, 0.7);
  background: rgba(59, 130, 246, 0.18);
}

.replay-controls__button--play {
  min-width: 62px;
}

.replay-controls__time {
  color: #cbd5e1;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  white-space: nowrap;
}

.replay-controls__seek {
  min-width: 120px;
  flex: 1;
}

.replay-controls__field {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #94a3b8;
  font-size: 12px;
}

.replay-controls__select {
  padding: 3px 6px;
  border: 1px solid rgba(148, 163, 184, 0.32);
  border-radius: 6px;
  background: #0f172a;
  color: #e5e7eb;
  font-size: 12px;
}

.replay-controls__volume {
  width: 88px;
}
</style>