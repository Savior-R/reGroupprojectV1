<script setup lang="ts">
import { computed } from 'vue'
import type { StreamType } from '../../types/recording'
import type { UploadProgress } from '../../utils/recorderUploader'

/**
 * Per-stream recording/upload indicator (05B §4, 09 §6.5).
 *
 * The page owns uploader progress; this component renders the three public
 * classroom streams in a stable order.
 */

const props = defineProps<{
  streams: Partial<Record<StreamType, UploadProgress>>
}>()

const STREAM_META: ReadonlyArray<{ type: StreamType; name: string }> = [
  { type: 'camera', name: '摄像头' },
  { type: 'screen', name: '屏幕' },
  { type: 'audio', name: '麦克风' },
]

const STATUS_LABELS: Record<UploadProgress['status'] | 'idle', string> = {
  idle: '未开始',
  uploading: '上传中',
  completed: '已完成',
  failed: '失败',
}

const rows = computed(() =>
  STREAM_META.map((meta) => {
    const progress = props.streams[meta.type] ?? null
    const status = progress?.status ?? 'idle'
    const percent =
      progress && progress.totalBytes > 0
        ? Math.min(100, Math.round((progress.uploadedBytes / progress.totalBytes) * 100))
        : null
    return {
      ...meta,
      status,
      statusLabel: STATUS_LABELS[status],
      percent,
    }
  }),
)
</script>

<template>
  <div class="recording-status" data-testid="recording-status">
    <span
      v-for="row in rows"
      :key="row.type"
      class="recording-status__item"
      :class="`recording-status__item--${row.status}`"
      :data-testid="`recording-${row.type}`"
    >
      <span class="recording-status__label">{{ row.name }}</span>
      <span class="recording-status__state">{{ row.statusLabel }}</span>
      <span v-if="row.percent !== null" class="recording-status__percent">
        {{ row.percent }}%
      </span>
    </span>
  </div>
</template>

<style scoped>
.recording-status {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.recording-status__item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 8px;
  border: 1px solid rgba(148, 163, 184, 0.25);
  border-radius: 999px;
  background: rgba(148, 163, 184, 0.1);
  font-size: 12px;
  white-space: nowrap;
}

.recording-status__label {
  color: #cbd5e1;
}

.recording-status__state {
  color: #94a3b8;
}

.recording-status__item--completed {
  border-color: rgba(74, 222, 128, 0.45);
}

.recording-status__item--completed .recording-status__state {
  color: #4ade80;
}

.recording-status__item--failed {
  border-color: rgba(248, 113, 113, 0.55);
}

.recording-status__item--failed .recording-status__state {
  color: #f87171;
}

.recording-status__percent {
  color: #94a3b8;
}
</style>