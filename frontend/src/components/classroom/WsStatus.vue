<script setup lang="ts">
import { computed } from 'vue'
import type { WsFailureReason, WsStatus as WsConnectionStatus } from '../../utils/wsClient'

/**
 * Control-channel connection indicator (05A §8, 09 §6.5).
 *
 * `reconnecting` already carries the seconds left in the grace window, so the
 * component renders the countdown without owning a timer.
 */

const props = defineProps<{
  status: WsConnectionStatus | null
}>()

const FAILURE_LABELS: Record<WsFailureReason, string> = {
  recording_lost: '录制已中断，无法重连',
  reconnect_timeout: '重连超时，连接已断开',
  connect_failed: '连接失败',
}

const label = computed(() => {
  const status = props.status
  if (!status) {
    return '未连接'
  }
  switch (status.status) {
    case 'connecting':
      return '连接中…'
    case 'connected':
      return '已连接'
    case 'reconnecting':
      return `重连中，剩余 ${status.secondsLeft} 秒`
    case 'failed':
      return `连接失败：${FAILURE_LABELS[status.reason]}`
    default:
      return '未连接'
  }
})

const phase = computed(() => props.status?.status ?? 'idle')
</script>

<template>
  <span
    class="ws-status"
    :class="`ws-status--${phase}`"
    data-testid="ws-status"
    role="status"
  >
    <span class="ws-status__dot" aria-hidden="true" />
    {{ label }}
  </span>
</template>

<style scoped>
.ws-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 10px;
  border: 1px solid rgba(148, 163, 184, 0.25);
  border-radius: 999px;
  background: rgba(148, 163, 184, 0.1);
  color: #cbd5e1;
  font-size: 12px;
  white-space: nowrap;
}

.ws-status__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #94a3b8;
}

.ws-status--connected {
  border-color: rgba(74, 222, 128, 0.45);
}

.ws-status--connected .ws-status__dot {
  background: #4ade80;
}

.ws-status--reconnecting {
  border-color: rgba(250, 204, 21, 0.5);
}

.ws-status--reconnecting .ws-status__dot {
  background: #facc15;
}

.ws-status--failed {
  border-color: rgba(248, 113, 113, 0.55);
  color: #fca5a5;
}

.ws-status--failed .ws-status__dot {
  background: #f87171;
}
</style>