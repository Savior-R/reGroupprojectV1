<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton, ElMessageBox, ElTag } from 'element-plus'
import ImmersiveLayout from '../../layouts/ImmersiveLayout.vue'
import ReplayPage from './ReplayPage.vue'
import { getRecordings, submitSession } from '../../api/replay'
import { getSession } from '../../api/sessions'
import { ApiError } from '../../types/api'
import type { RecordingStreamView, StreamType } from '../../types/recording'
import type { SessionMode, SessionStatus, SessionView } from '../../types/session'
import { messageOf } from '../../utils/message'

/**
 * Review / submit page (05B §7.3, 09 §6.6/§10.4).
 *
 * Uploads may still be running when the room navigates here, so the page polls
 * `GET /recordings` every 2 s and only enables submission once every stream
 * required by the mode is `uploaded`. The replay manifest itself is not
 * available before that point. After a successful submit the manifest is
 * refetched (fresh presigned URLs) and the page renders a read-only replay.
 */

const POLL_INTERVAL_MS = 2_000
/** No upload progress for this long: the recording is probably dead. */
const STALL_TIMEOUT_MS = 60_000

const REQUIRED_STREAMS: Record<SessionMode, readonly StreamType[]> = {
  free: ['camera', 'screen', 'audio'],
  scenario: ['camera', 'screen', 'audio'],
  interview: ['camera', 'audio'],
}

const STREAM_NAMES: Record<StreamType, string> = {
  camera: '摄像头',
  screen: '屏幕',
  audio: '麦克风',
}

const STREAM_STATUS_LABELS: Record<RecordingStreamView['status'] | 'missing', string> = {
  missing: '等待上传',
  uploading: '上传中',
  uploaded: '已完成',
  failed: '失败',
  deleted: '已删除',
}

const SESSION_STATUS_LABELS: Record<SessionStatus, string> = {
  preparing: '准备中',
  in_progress: '进行中',
  ended: '已结束',
  reviewing: '待提交',
  submitted: '已提交',
  cancelled: '已取消',
  failed: '失败',
}

const MODE_LABELS: Record<SessionMode, string> = {
  free: '自由试讲',
  scenario: '情景试讲',
  interview: '结构化面试',
}

const route = useRoute()
const router = useRouter()

const sessionId = computed(() => String(route.params.sessionId ?? ''))

/** `?t={ms}` opens the replay at a report / issue timestamp. */
const initialTimeMs = computed(() => {
  const raw = route.query.t
  const value = Array.isArray(raw) ? raw[0] : raw
  const parsed = Number(value)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : 0
})

const session = ref<SessionView | null>(null)
const recordings = ref<RecordingStreamView[]>([])
const loadingSession = ref(true)
const refreshingRecordings = ref(false)
const submitting = ref(false)
const submitted = ref(false)
const uploadStalled = ref(false)
const loadError = ref('')
const recordingsError = ref('')
const submitError = ref('')

const replayPageRef = ref<InstanceType<typeof ReplayPage> | null>(null)

let pollTimer: ReturnType<typeof setInterval> | null = null
let stallTimer: ReturnType<typeof setTimeout> | null = null
let lastProgressSignature = ''
let disposed = false

const requiredTypes = computed<readonly StreamType[]>(() =>
  session.value ? REQUIRED_STREAMS[session.value.mode] : [],
)

function streamOf(type: StreamType): RecordingStreamView | null {
  return recordings.value.find((row) => row.stream_type === type) ?? null
}

const streamRows = computed(() =>
  requiredTypes.value.map((type) => {
    const row = streamOf(type)
    const status = row?.status ?? 'missing'
    return {
      type,
      name: STREAM_NAMES[type],
      status,
      statusLabel: STREAM_STATUS_LABELS[status],
      row,
    }
  }),
)

const allUploaded = computed(
  () =>
    requiredTypes.value.length > 0 &&
    requiredTypes.value.every((type) => streamOf(type)?.status === 'uploaded'),
)

const anyFailed = computed(() =>
  requiredTypes.value.some((type) => {
    const status = streamOf(type)?.status
    return status === 'failed' || status === 'deleted'
  }),
)

const canSubmitStatus = computed(
  () => session.value?.status === 'ended' || session.value?.status === 'reviewing',
)

const canSubmit = computed(
  () =>
    allUploaded.value &&
    canSubmitStatus.value &&
    !submitting.value &&
    !submitted.value,
)

/** The player only mounts once a complete manifest can exist. */
const replayVisible = computed(() => submitted.value || allUploaded.value)

const taskPath = computed(() =>
  session.value?.task_id
    ? `/student/tasks/${session.value.task_id}`
    : '/student/tasks',
)

const title = computed(() =>
  session.value ? `${MODE_LABELS[session.value.mode]}回看` : '回看与提交',
)

const statusLabel = computed(() =>
  session.value ? SESSION_STATUS_LABELS[session.value.status] : '',
)

function formatBytes(bytes: number): string {
  if (!bytes || bytes <= 0) {
    return '0 B'
  }
  if (bytes < 1024) {
    return `${bytes} B`
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`
  }
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function stopPolling(): void {
  if (pollTimer !== null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
  clearStallTimer()
}

function clearStallTimer(): void {
  if (stallTimer !== null) {
    clearTimeout(stallTimer)
    stallTimer = null
  }
}

function progressSignature(rows: RecordingStreamView[]): string {
  return rows
    .map(
      (row) =>
        `${row.stream_type}:${row.status}:${row.parts_uploaded}:${row.bytes_uploaded}`,
    )
    .join('|')
}

/** Re-arms the dead-upload watchdog; only actual progress resets the clock. */
function armStallTimer(): void {
  clearStallTimer()
  if (disposed || submitted.value) {
    return
  }
  stallTimer = setTimeout(() => {
    stallTimer = null
    stopPolling()
    uploadStalled.value = true
  }, STALL_TIMEOUT_MS)
}

function startPolling(): void {
  if (pollTimer !== null || disposed) {
    return
  }
  pollTimer = setInterval(() => {
    void refreshRecordings()
  }, POLL_INTERVAL_MS)
  armStallTimer()
}

async function refreshRecordings(): Promise<void> {
  if (disposed || refreshingRecordings.value) {
    return
  }
  refreshingRecordings.value = true
  recordingsError.value = ''
  try {
    const rows = await getRecordings(sessionId.value)
    if (disposed) {
      return
    }
    const signature = progressSignature(rows)
    if (signature !== lastProgressSignature) {
      lastProgressSignature = signature
      armStallTimer()
    }
    recordings.value = rows
    if (allUploaded.value || anyFailed.value) {
      stopPolling()
    }
  } catch (value) {
    if (disposed) {
      return
    }
    stopPolling()
    recordingsError.value = messageOf(value, '录制状态加载失败，请重试。')
  } finally {
    if (!disposed) {
      refreshingRecordings.value = false
    }
  }
}

async function retryRecordings(): Promise<void> {
  uploadStalled.value = false
  lastProgressSignature = ''
  await refreshRecordings()
  if (!allUploaded.value && !anyFailed.value && !recordingsError.value) {
    startPolling()
  }
}

async function init(): Promise<void> {
  if (!sessionId.value) {
    loadError.value = '缺少会话 ID。'
    loadingSession.value = false
    return
  }

  try {
    session.value = await getSession(sessionId.value)
  } catch (value) {
    loadError.value = messageOf(value, '会话加载失败，请重试。')
    loadingSession.value = false
    return
  }
  loadingSession.value = false
  if (disposed) {
    return
  }

  if (session.value.status === 'submitted') {
    submitted.value = true
    return
  }
  if (
    session.value.status === 'preparing' ||
    session.value.status === 'in_progress'
  ) {
    loadError.value = '会话尚未结束，无法进入回看。'
    return
  }
  if (session.value.status === 'failed' || session.value.status === 'cancelled') {
    loadError.value = '该会话已结束，无法回看或提交。'
    return
  }

  await refreshRecordings()
  if (!allUploaded.value && !anyFailed.value && !recordingsError.value) {
    startPolling()
  }
}

function detailStreams(details: Record<string, unknown> | undefined, key: string): string {
  const value = details?.[key]
  return Array.isArray(value) ? value.map(String).join('、') : ''
}

async function submit(): Promise<void> {
  if (!canSubmit.value) {
    return
  }
  try {
    await ElMessageBox.confirm(
      '提交后将无法修改，且其他未提交的尝试会被删除。确认提交本次录制吗？',
      '确认提交',
      {
        confirmButtonText: '确认提交',
        cancelButtonText: '取消',
        type: 'warning',
      },
    )
  } catch {
    return
  }
  if (!canSubmit.value) {
    return
  }

  submitting.value = true
  submitError.value = ''
  try {
    await submitSession(sessionId.value)
    if (disposed) {
      return
    }
    // The backend exposes no submission-detail endpoint, so the id returned
    // here is not shown; a later reload of a submitted session behaves the same.
    submitted.value = true
    stopPolling()
    if (session.value) {
      session.value = { ...session.value, status: 'submitted' }
    }
    // Presigned URLs may have expired while the student watched; reload the
    // manifest and keep both the player state and its current position.
    await replayPageRef.value?.reload({ preservePosition: true })
  } catch (value) {
    if (disposed) {
      return
    }
    if (value instanceof ApiError) {
      const missing = detailStreams(value.details, 'missing_streams')
      const invalid = detailStreams(value.details, 'invalid_streams')
      const reasons: string[] = []
      if (missing) {
        reasons.push(`缺少：${missing}`)
      }
      if (invalid) {
        reasons.push(`无效：${invalid}`)
      }
      submitError.value = reasons.length
        ? `${value.message}（${reasons.join('；')}）`
        : value.message
      if (value.status === 422) {
        // The upload states may have changed since the last poll.
        await refreshRecordings()
      }
    } else {
      submitError.value = messageOf(value, '提交失败，请重试。')
    }
  } finally {
    if (!disposed) {
      submitting.value = false
    }
  }
}

function leaveToTask(): void {
  void router.push(taskPath.value)
}

onMounted(() => {
  void init()
})

onBeforeUnmount(() => {
  disposed = true
  stopPolling()
})
</script>

<template>
  <ImmersiveLayout
    :title="title"
    :back-to="taskPath"
    :exit-confirm="false"
    @exit="leaveToTask"
  >
    <template #status>
      <ElTag v-if="session" type="info" data-testid="review-status">
        {{ statusLabel }}
      </ElTag>
    </template>

    <div class="review-page">
      <p v-if="loadingSession" class="review-page__state" data-testid="review-loading">
        加载会话中…
      </p>

      <ElAlert
        v-else-if="loadError"
        class="review-page__state"
        type="error"
        :closable="false"
        show-icon
        data-testid="review-load-error"
      >
        <template #title>{{ loadError }}</template>
      </ElAlert>

      <template v-else-if="session">
        <ElAlert
          v-if="recordingsError"
          class="review-page__state"
          type="error"
          :closable="false"
          show-icon
          data-testid="recordings-error"
        >
          <template #title>{{ recordingsError }}</template>
          <ElButton size="small" data-testid="recordings-retry" @click="retryRecordings">
            重试
          </ElButton>
        </ElAlert>

        <ElAlert
          v-if="anyFailed"
          class="review-page__state"
          type="error"
          :closable="false"
          show-icon
          data-testid="recording-failed"
        >
          <template #title>录制上传失败，本次无法提交，请选择重做。</template>
        </ElAlert>

        <ElAlert
          v-if="uploadStalled"
          class="review-page__state"
          type="warning"
          :closable="false"
          show-icon
          data-testid="upload-stalled"
        >
          <template #title>录制可能已中断，请重做。</template>
          <ElButton size="small" data-testid="upload-stalled-retry" @click="retryRecordings">
            重新检查
          </ElButton>
        </ElAlert>

        <ElAlert
          v-if="submitError"
          class="review-page__state"
          type="error"
          :closable="false"
          show-icon
          data-testid="submit-error"
        >
          <template #title>{{ submitError }}</template>
        </ElAlert>

        <section v-if="!submitted" class="review-page__progress" data-testid="review-streams">
          <h2 class="review-page__section-title">录制上传进度</h2>
          <ul class="review-page__stream-list">
            <li
              v-for="row in streamRows"
              :key="row.type"
              class="review-page__stream"
              :class="`review-page__stream--${row.status}`"
              :data-testid="`stream-progress-${row.type}`"
            >
              <span class="review-page__stream-name">{{ row.name }}</span>
              <span class="review-page__stream-status">{{ row.statusLabel }}</span>
              <span v-if="row.row" class="review-page__stream-meta">
                {{ row.row.parts_uploaded }} 片 / {{ formatBytes(row.row.bytes_uploaded) }}
              </span>
            </li>
          </ul>
          <p
            v-if="!allUploaded && !anyFailed"
            class="review-page__hint"
            data-testid="upload-pending"
          >
            正在等待录制上传完成…
          </p>
        </section>

        <ReplayPage
          v-if="replayVisible"
          ref="replayPageRef"
          :session-id="sessionId"
          :initial-time-ms="initialTimeMs"
        />

        <div v-if="submitted" class="review-page__readonly" data-testid="review-submitted">
          <ElTag type="success">已提交</ElTag>
          <span class="review-page__readonly-text">
            回放为只读，提交件可在课程结束前查看。
          </span>
        </div>

        <footer v-else class="review-page__actions">
          <ElButton
            type="primary"
            data-testid="submit-session"
            :loading="submitting"
            :disabled="!canSubmit"
            @click="submit"
          >
            提交
          </ElButton>
          <ElButton data-testid="redo-session" :disabled="submitting" @click="leaveToTask">
            重做
          </ElButton>
        </footer>
      </template>
    </div>
  </ImmersiveLayout>
</template>

<style scoped>
.review-page {
  display: flex;
  min-height: 0;
  flex: 1;
  flex-direction: column;
  gap: 12px;
  padding: 14px;
}

.review-page__state {
  margin: 0;
}

.review-page__progress {
  flex: none;
}

.review-page__section-title {
  margin: 0 0 8px;
  font-size: 14px;
  font-weight: 600;
}

.review-page__stream-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.review-page__stream {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 5px 10px;
  border: 1px solid rgba(148, 163, 184, 0.28);
  border-radius: 999px;
  background: rgba(148, 163, 184, 0.1);
  font-size: 12px;
}

.review-page__stream--uploaded {
  border-color: rgba(74, 222, 128, 0.5);
}

.review-page__stream--failed,
.review-page__stream--deleted {
  border-color: rgba(248, 113, 113, 0.55);
}

.review-page__stream-name {
  color: #cbd5e1;
}

.review-page__stream-status {
  color: #93c5fd;
}

.review-page__stream--uploaded .review-page__stream-status {
  color: #4ade80;
}

.review-page__stream--failed .review-page__stream-status {
  color: #f87171;
}

.review-page__stream-meta {
  color: #94a3b8;
}

.review-page__hint {
  margin: 8px 0 0;
  color: #94a3b8;
  font-size: 12px;
}

.review-page__readonly {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border: 1px solid rgba(74, 222, 128, 0.35);
  border-radius: 10px;
  background: rgba(22, 101, 52, 0.16);
  font-size: 13px;
}

.review-page__readonly-text {
  color: #bbf7d0;
}

.review-page__actions {
  display: flex;
  flex: none;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border: 1px solid rgba(148, 163, 184, 0.2);
  border-radius: 12px;
  background: rgba(15, 23, 42, 0.86);
}
</style>