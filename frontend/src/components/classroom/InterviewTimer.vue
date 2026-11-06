<script setup lang="ts">
import { computed } from 'vue'

/**
 * Interview total-remaining-time display (05C §6.3, 09 §6.5).
 *
 * The server owns the clock: this component only formats the latest
 * `remaining_ms` from the snapshot or `session.time_tick`. It never runs a
 * local countdown, so business decisions (next question / end) cannot drift
 * from the authoritative server state.
 */

const props = defineProps<{
  remainingMs: number | null
}>()

const totalSeconds = computed(() =>
  props.remainingMs === null ? null : Math.max(0, Math.ceil(props.remainingMs / 1000)),
)

const label = computed(() => {
  if (totalSeconds.value === null) {
    return '--:--'
  }
  const minutes = Math.floor(totalSeconds.value / 60)
    .toString()
    .padStart(2, '0')
  const seconds = (totalSeconds.value % 60).toString().padStart(2, '0')
  return `${minutes}:${seconds}`
})

const urgent = computed(() => totalSeconds.value !== null && totalSeconds.value <= 30)
const warning = computed(
  () => totalSeconds.value !== null && totalSeconds.value > 30 && totalSeconds.value <= 60,
)
</script>

<template>
  <div
    class="interview-timer"
    :class="{
      'interview-timer--warning': warning,
      'interview-timer--urgent': urgent,
    }"
    data-testid="interview-timer"
    role="timer"
    aria-label="剩余总时间"
  >
    <span class="interview-timer__label">剩余总时间</span>
    <span class="interview-timer__value">{{ label }}</span>
  </div>
</template>

<style scoped>
.interview-timer {
  display: inline-flex;
  align-items: baseline;
  gap: 10px;
  padding: 10px 16px;
  border: 1px solid rgba(148, 163, 184, 0.25);
  border-radius: 12px;
  background: rgba(148, 163, 184, 0.1);
}

.interview-timer__label {
  color: #94a3b8;
  font-size: 12px;
}

.interview-timer__value {
  color: #e5e7eb;
  font-size: 24px;
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}

.interview-timer--warning {
  border-color: rgba(250, 204, 21, 0.5);
}

.interview-timer--warning .interview-timer__value {
  color: #facc15;
}

.interview-timer--urgent {
  border-color: rgba(248, 113, 113, 0.55);
}

.interview-timer--urgent .interview-timer__value {
  color: #f87171;
}
</style>