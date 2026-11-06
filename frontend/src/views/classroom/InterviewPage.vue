<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton, ElMessageBox } from 'element-plus'
import ImmersiveLayout from '../../layouts/ImmersiveLayout.vue'
import CameraPreview from '../../components/classroom/CameraPreview.vue'
import InterviewTimer from '../../components/classroom/InterviewTimer.vue'
import QuestionPanel from '../../components/classroom/QuestionPanel.vue'
import RecordingStatus from '../../components/classroom/RecordingStatus.vue'
import WsStatus from '../../components/classroom/WsStatus.vue'
import { recordingsApi } from '../../api/recordings'
import { cancelSession, getSession } from '../../api/sessions'
import { fetchWsTicket } from '../../api/wsTicket'
import type { ClientCommandType, EventPayload } from '../../types/event'
import type { StreamType } from '../../types/recording'
import type { ServerEvent, SessionStatus, SessionView } from '../../types/session'
import { classroomReducer, createInitialClassroomState } from '../../utils/classroomReducer'
import type { ClassroomState } from '../../utils/classroomReducer'
import { acquireMedia, createRecorder, STREAM_MIME } from '../../utils/media'
import type { AcquiredMedia } from '../../utils/media'
import { messageOf } from '../../utils/message'
import { RecorderUploader } from '../../utils/recorderUploader'
import type { UploadProgress } from '../../utils/recorderUploader'
import { createWsClient, WS_GRACE_MS } from '../../utils/wsClient'
import type { WsClient, WsStatus as WsConnectionStatus } from '../../utils/wsClient'

/**
 * Structured interview room (05C §6-§7, 05B §3, 09 §6.5).
 *
 * Flow: load the session, show an explicit start gate, then acquire camera +
 * microphone only (the server rejects a screen stream for interviews), create
 * one recorder/uploader per stream, connect the control channel and report
 * `recording.ready` with the two streams on the first snapshot. Multipart
 * uploads start only after
 * `recording.ready_ack.ok`; `session.start` follows once they are running.
 *
 * Question text, order/total and the total remaining time come exclusively
 * from the reducer (snapshot / `interview.*` events / `session.time_tick`).
 * There is no local business countdown, no virtual-student grid, no discipline
 * UI and no answer points: examiner text and all privileged data stay on the
 * server.
 */

const STREAM_ORDER: readonly StreamType[] = ['camera', 'audio']
const END_CONFIRM_TIMEOUT_MS = 10_000

const WS_FAILURE_MESSAGES: Record<'recording_lost' | 'reconnect_timeout' | 'connect_failed', string> = {
  recording_lost: '录制已中断，无法重连。',
  reconnect_timeout: '连接重连超时，本次面试已中断。',
  connect_failed: '无法连接面试服务，请检查网络后重试。',
}

const route = useRoute()
const router = useRouter()

const sessionId = computed(() => String(route.params.sessionId ?? ''))
const reviewPath = computed(() => `/student/sessions/${sessionId.value}/review`)

const session = ref<SessionView | null>(null)
const room = ref<ClassroomState>(createInitialClassroomState())
const wsStatus = ref<WsConnectionStatus | null>(null)
const media = shallowRef<AcquiredMedia | null>(null)
const progress = ref<Partial<Record<StreamType, UploadProgress>>>({})
const loadError = ref('')
const failure = ref<string | null>(null)
const endError = ref<string | null>(null)
const ending = ref(false)
/** Loaded an `in_progress` session: no local recorders exist for it. */
const resumeLostMode = ref(false)
/** `preparing` session loaded: wait for an explicit start click before capture. */
const gateVisible = ref(false)
const roomStarting = ref(false)
/** Recorders are running: guard unload / route changes until they stop. */
const recordersActive = ref(false)

const uploaders = new Map<StreamType, RecorderUploader>()
let ws: WsClient | null = null
let disposed = false
let recordingLost = false
let readySent = false
let resumeSent = false
let startRequested = false
let startAttempted = false
let sessionPreparing = false
let cancelRequested = false
let endCommandPending = false
let finishStarted = false
let endWatchdog: ReturnType<typeof setTimeout> | null = null
let uploadsPromise: Promise<boolean> | null = null
let connectionWaiter: ((connected: boolean) => void) | null = null
let connectionTimeout: ReturnType<typeof setTimeout> | null = null

const title = computed(() => {
  const topic = room.value.publicConfig.topic
  return typeof topic === 'string' && topic.trim() ? topic : '结构化面试'
})

const question = computed(() => room.value.interview.current)
const remainingMs = computed(() => room.value.interview.remainingMs)
const controlsFailed = computed(
  () => failure.value !== null || loadError.value !== '' || resumeLostMode.value,
)
const baseDisabled = computed(
  () => controlsFailed.value || ending.value || room.value.status !== 'in_progress',
)
/** The last question (order === total) has no successor. */
const nextDisabled = computed(() => {
  const current = question.value
  return baseDisabled.value || !current || current.order >= current.total
})

function isTerminal(status: SessionStatus): boolean {
  return status === 'ended' || status === 'reviewing' || status === 'submitted'
}

function addRecorder(type: StreamType, stream: MediaStream): void {
  const recorder = createRecorder(stream, STREAM_MIME[type])
  const uploader = new RecorderUploader(recordingsApi, sessionId.value, type, recorder, {
    onProgress: (next) => {
      progress.value = { ...progress.value, [type]: next }
    },
    onError: (error) => {
      handleRecordingFailure(messageOf(error, '录制失败，请检查设备后重试。'))
    },
  })
  uploaders.set(type, uploader)
}

function prepareRecorders(): void {
  const acquired = media.value
  if (!acquired) {
    throw new Error('媒体流未就绪')
  }
  // Interviews record camera + microphone only; a screen stream is never
  // acquired for this mode and must not be reported to the server.
  addRecorder('camera', acquired.camera)
  addRecorder('audio', acquired.audio)
  setRecordersActive(true)
}

function setRecordersActive(active: boolean): void {
  if (recordersActive.value === active) {
    return
  }
  recordersActive.value = active
  if (active) {
    window.addEventListener('beforeunload', handleBeforeUnload)
  } else {
    window.removeEventListener('beforeunload', handleBeforeUnload)
  }
}

function handleBeforeUnload(event: BeforeUnloadEvent): void {
  event.preventDefault()
  event.returnValue = ''
}

/**
 * A `preparing` session that never reached `session.start` would keep blocking
 * new attempts, so every pre-start exit path (failure, navigation, unmount)
 * cancels it best-effort. Once `session.start` has been sent the server owns
 * the lifecycle and cancellation would be destructive.
 */
function cancelPreparingSession(): void {
  if (!sessionPreparing || startAttempted || cancelRequested) {
    return
  }
  cancelRequested = true
  const targetId = session.value?.session_id ?? sessionId.value
  try {
    void Promise.resolve(cancelSession(targetId)).catch(() => undefined)
  } catch {
    // Best effort: the page is already leaving or showing a failure.
  }
}

onBeforeRouteLeave(async () => {
  if (!recordersActive.value) {
    return true
  }
  try {
    await ElMessageBox.confirm(
      '录制正在进行，离开将中断本次面试且当前录制会丢失，确定离开吗？',
      '离开确认',
      {
        confirmButtonText: '离开',
        cancelButtonText: '继续面试',
        type: 'warning',
      },
    )
    return true
  } catch {
    return false
  }
})

async function startUploaders(): Promise<void> {
  const results = await Promise.allSettled(
    [...uploaders.values()].map((uploader) => uploader.start()),
  )
  const rejected = results.find(
    (result): result is PromiseRejectedResult => result.status === 'rejected',
  )
  if (rejected) {
    throw rejected.reason
  }
}

function streamStatuses(): Array<{ stream_type: StreamType; status: 'ready' | 'unavailable' }> {
  // Recorders are created (and already recording) before `recording.ready`;
  // uploaders only start after the backend acknowledges the stream report.
  return STREAM_ORDER.map((type) => ({
    stream_type: type,
    status: uploaders.has(type) ? 'ready' : 'unavailable',
  }))
}

function sendCommand(type: ClientCommandType, payload: EventPayload = {}): boolean {
  if (room.value.lastError) {
    room.value = { ...room.value, lastError: null }
  }
  return ws?.send(type, payload) ?? false
}

async function beginRecordingStart(): Promise<void> {
  if (startRequested) {
    return
  }
  startRequested = true
  try {
    await startUploaders()
  } catch (error) {
    handleRecordingFailure(messageOf(error, '录制启动失败，请检查设备后重试。'))
    return
  }
  if (disposed) {
    for (const uploader of uploaders.values()) {
      void uploader.abort()
    }
    media.value?.stop()
    return
  }
  if (failure.value) {
    return
  }

  if (sendCommand('session.start')) {
    markStartAttempted()
    return
  }

  // The control channel may have dropped while multipart uploads were
  // starting. Wait inside the reconnect grace and retry once before failing.
  const connected = await waitForConnection(WS_GRACE_MS)
  if (disposed) {
    for (const uploader of uploaders.values()) {
      void uploader.abort()
    }
    media.value?.stop()
    return
  }
  if (failure.value) {
    return
  }
  if (connected && sendCommand('session.start')) {
    markStartAttempted()
    return
  }
  handleRecordingFailure('无法开始面试：与面试服务的连接已断开。')
}

function markStartAttempted(): void {
  startAttempted = true
  sessionPreparing = false
}

function handleSnapshot(): void {
  if (readySent || room.value.status !== 'preparing' || uploaders.size === 0) {
    return
  }
  readySent = true
  sendCommand('recording.ready', { streams: streamStatuses() })
}

function clearEndWatchdog(): void {
  if (endWatchdog !== null) {
    clearTimeout(endWatchdog)
    endWatchdog = null
  }
}

function armEndWatchdog(): void {
  clearEndWatchdog()
  endWatchdog = setTimeout(() => {
    endWatchdog = null
    if (disposed || finishStarted) {
      return
    }
    endCommandPending = false
    endError.value =
      '结束面试超时：服务器未确认结束，录制已停止并继续上传，请检查网络后重试。'
    ending.value = false
    media.value?.stop()
  }, END_CONFIRM_TIMEOUT_MS)
}

/** Starts the single upload-and-navigate finish flow once the interview ends. */
function ensureFinish(): void {
  if (finishStarted) {
    return
  }
  finishStarted = true
  clearEndWatchdog()
  settleConnectionWait(false)
  endCommandPending = false
  endError.value = null
  ending.value = true
  void finishAfterUploads(stopAllUploaders())
}

function handleServerEvent(event: ServerEvent): void {
  room.value = classroomReducer(room.value, event)
  if (room.value.status !== null && room.value.status !== 'preparing') {
    // The server owns the session now (started/terminal); never cancel it.
    sessionPreparing = false
  }

  switch (event.type) {
    case 'session.snapshot':
      handleSnapshot()
      return

    case 'recording.ready_ack':
      if (event.payload.ok === true) {
        void beginRecordingStart()
      } else {
        handleRecordingFailure('录制流未全部就绪，无法开始面试。')
      }
      return

    case 'interview.ended':
      // The server emits `interview.ended` immediately before `session.ended`;
      // either one is terminal for the room, so the finish flow starts here
      // without waiting for the follow-up event.
      ensureFinish()
      return

    case 'session.ended':
      ws?.close()
      ensureFinish()
      return

    case 'session.failed': {
      // A failed session is terminal; release the end flow as well so an
      // in-flight “结束面试” cannot wait forever.
      endCommandPending = false
      clearEndWatchdog()
      const code =
        typeof event.payload.error_code === 'string' ? `（${event.payload.error_code}）` : ''
      handleRecordingFailure(`会话失败${code}，本次面试无法继续。`)
      return
    }

    case 'error': {
      // The reducer stores the server error for the visible banner. While an
      // end command is pending, the same error terminates the end flow so the
      // page cannot stay stuck on “结束中…”.
      if (endCommandPending) {
        endCommandPending = false
        clearEndWatchdog()
        const code = typeof event.payload.code === 'string' ? event.payload.code : 'ERROR'
        const message =
          typeof event.payload.message === 'string' ? event.payload.message : '指令执行失败'
        endError.value = `结束面试失败（${code}）：${message}`
        ending.value = false
        media.value?.stop()
      }
      return
    }

    default:
  }
}

function settleConnectionWait(connected: boolean): void {
  if (connectionTimeout !== null) {
    clearTimeout(connectionTimeout)
    connectionTimeout = null
  }
  const waiter = connectionWaiter
  connectionWaiter = null
  waiter?.(connected)
}

/**
 * Resolves once the control channel can send commands, bounded by the 30 s
 * reconnect grace. Returns immediately when already connected or failed.
 */
function waitForConnection(timeoutMs: number): Promise<boolean> {
  const current = wsStatus.value?.status
  if (current === 'connected') {
    return Promise.resolve(true)
  }
  if (current === 'failed') {
    return Promise.resolve(false)
  }
  settleConnectionWait(false)
  return new Promise<boolean>((resolve) => {
    connectionWaiter = resolve
    connectionTimeout = setTimeout(() => {
      connectionWaiter = null
      connectionTimeout = null
      resolve(false)
    }, timeoutMs)
  })
}

function handleConnectionStatus(status: WsConnectionStatus): void {
  wsStatus.value = status
  if (status.status === 'failed') {
    settleConnectionWait(false)
    handleRecordingFailure(WS_FAILURE_MESSAGES[status.reason])
    return
  }
  if (status.status !== 'connected') {
    return
  }
  // A fresh page load cannot own the previous page's recorders: report the
  // recording as lost so the server fails the session instead of accepting a
  // half-resumed session (05A §8, brief: refresh => recording_state=lost).
  if (resumeLostMode.value && !resumeSent) {
    resumeSent = true
    ws?.resume()
  }
  // Resolve after the ws client has sent its own `session.resume` for a
  // reconnect, so an end command is never ordered before it.
  queueMicrotask(() => settleConnectionWait(true))
}

function handleRecordingFailure(message: string): void {
  if (failure.value) {
    return
  }
  failure.value = message
  recordingLost = true
  ending.value = false
  endCommandPending = false
  clearEndWatchdog()
  settleConnectionWait(false)
  cancelPreparingSession()
  setRecordersActive(false)
  for (const uploader of uploaders.values()) {
    void uploader.abort()
  }
  media.value?.stop()
  ws?.close()
}

function stopAllUploaders(): Promise<boolean> {
  setRecordersActive(false)
  if (!uploadsPromise) {
    uploadsPromise = (async () => {
      const results = await Promise.allSettled(
        [...uploaders.values()].map((uploader) => uploader.stop()),
      )
      const rejected = results.find(
        (result): result is PromiseRejectedResult => result.status === 'rejected',
      )
      if (rejected) {
        // Same cleanup as a live recording failure: abort the remaining
        // uploads, stop the tracks and close the control channel.
        handleRecordingFailure(messageOf(rejected.reason, '录制上传失败，请重试。'))
        return false
      }
      return true
    })()
  }
  return uploadsPromise
}

async function finishAfterUploads(stopPromise: Promise<boolean>): Promise<void> {
  const uploaded = await stopPromise
  if (disposed) {
    return
  }
  if (!uploaded) {
    if (!failure.value) {
      media.value?.stop()
    }
    ending.value = false
    return
  }
  media.value?.stop()
  if (failure.value) {
    ending.value = false
    return
  }
  await router.push(reviewPath.value)
}

async function endInterview(): Promise<void> {
  if (ending.value || failure.value || room.value.status !== 'in_progress') {
    return
  }
  ending.value = true
  endError.value = null

  // Stop the recorders first, then ask the server to end; the final parts keep
  // uploading over REST while the control channel delivers `interview.ended`
  // and `session.ended`.
  stopAllUploaders()

  // A reconnect may be in progress: wait inside the 30 s grace instead of
  // aborting the recording data. The uploads keep running meanwhile.
  const connected = await waitForConnection(WS_GRACE_MS)
  if (disposed) {
    return
  }
  if (finishStarted || room.value.status !== 'in_progress') {
    // A terminal event already arrived and the finish flow owns the rest.
    return
  }
  if (failure.value) {
    ending.value = false
    return
  }
  if (!connected) {
    endError.value =
      '无法连接面试服务，结束指令未发送；录制已停止并继续上传，请恢复网络后重试。'
    ending.value = false
    media.value?.stop()
    return
  }

  if (!sendCommand('interview.end')) {
    endError.value = '结束指令发送失败；录制已停止并继续上传，请重试。'
    ending.value = false
    media.value?.stop()
    return
  }
  endCommandPending = true
  armEndWatchdog()
}

function nextQuestion(): void {
  if (nextDisabled.value) {
    return
  }
  sendCommand('interview.next_question')
}

async function confirmExit(): Promise<void> {
  if (ending.value) {
    return
  }
  if (failure.value || endError.value || room.value.status !== 'in_progress') {
    await router.push('/student/tasks')
    return
  }
  try {
    await ElMessageBox.confirm('结束本次面试并上传录制吗？', '结束面试', {
      confirmButtonText: '结束并上传',
      cancelButtonText: '继续面试',
      type: 'warning',
    })
  } catch {
    return
  }
  await endInterview()
}

function handleBack(): void {
  void confirmExit()
}

function connectControlChannel(): void {
  ws = createWsClient({
    sessionId: sessionId.value,
    ticketProvider: () => fetchWsTicket(sessionId.value),
    onEvent: handleServerEvent,
    onStatus: handleConnectionStatus,
    recordingState: () => (recordingLost || resumeLostMode.value ? 'lost' : 'active'),
  })
  void ws.connect()
}

/**
 * Entry point from the start gate. The click is the user gesture browsers
 * require before media capture; nothing is acquired on mount.
 */
async function startRoom(): Promise<void> {
  if (!gateVisible.value || roomStarting.value || !session.value) {
    return
  }
  roomStarting.value = true
  gateVisible.value = false

  try {
    // Interview sessions require camera + microphone only; `acquireMedia`
    // never requests screen sharing for this mode.
    media.value = await acquireMedia('interview')
  } catch (error) {
    failure.value = messageOf(error, '无法获取摄像头或麦克风，请检查设备权限后重试。')
    cancelPreparingSession()
    return
  }
  if (disposed) {
    media.value?.stop()
    media.value = null
    return
  }

  try {
    prepareRecorders()
  } catch (error) {
    handleRecordingFailure(messageOf(error, '录制启动失败，请检查设备后重试。'))
    return
  }
  if (disposed || failure.value) {
    return
  }

  connectControlChannel()
}

async function init(): Promise<void> {
  if (!sessionId.value) {
    loadError.value = '缺少会话 ID。'
    return
  }

  try {
    session.value = await getSession(sessionId.value)
  } catch (error) {
    loadError.value = messageOf(error, '会话加载失败，请重试。')
    return
  }
  if (disposed) {
    // The page was left while the session was still loading: a `preparing`
    // session would otherwise block the next attempt.
    if (session.value.status === 'preparing') {
      sessionPreparing = true
      cancelPreparingSession()
    }
    return
  }

  if (isTerminal(session.value.status)) {
    // `session.ended` normally navigates here; a refresh on an already ended
    // session goes straight to the review room.
    await router.replace(reviewPath.value)
    return
  }
  if (session.value.status === 'failed' || session.value.status === 'cancelled') {
    failure.value = '该会话已结束，无法继续面试。'
    return
  }

  if (session.value.status === 'in_progress') {
    // A page reload cannot own the recorders of the previous page. Do not
    // capture media or create uploaders; connect and report the lost recording
    // so the server decides the terminal state.
    resumeLostMode.value = true
    connectControlChannel()
    return
  }

  // `preparing`: wait for the user gesture before requesting media.
  sessionPreparing = true
  gateVisible.value = true
}

onMounted(() => {
  void init()
})

onBeforeUnmount(() => {
  disposed = true
  cancelPreparingSession()
  setRecordersActive(false)
  clearEndWatchdog()
  settleConnectionWait(false)
  ws?.close()
  media.value?.stop()
  for (const uploader of uploaders.values()) {
    void uploader.abort()
  }
})
</script>

<template>
  <ImmersiveLayout
    :title="title"
    :exit-confirm="false"
    @back="handleBack"
    @exit="confirmExit"
  >
    <template #status>
      <WsStatus :status="wsStatus" />
      <RecordingStatus :streams="progress" :stream-types="STREAM_ORDER" />
    </template>

    <div class="interview-page">
      <ElAlert
        v-if="loadError"
        class="interview-page__alert"
        type="error"
        :closable="false"
        show-icon
        data-testid="interview-load-error"
      >
        <template #title>{{ loadError }}</template>
      </ElAlert>

      <ElAlert
        v-if="failure"
        class="interview-page__alert"
        type="error"
        :closable="false"
        show-icon
        data-testid="interview-failure"
      >
        <template #title>录制或会话失败</template>
        <p class="interview-page__failure-message">{{ failure }}</p>
      </ElAlert>

      <ElAlert
        v-if="endError"
        class="interview-page__alert"
        type="warning"
        :closable="false"
        show-icon
        data-testid="interview-end-error"
      >
        <template #title>结束面试未完成</template>
        <p class="interview-page__failure-message">{{ endError }}</p>
      </ElAlert>

      <ElAlert
        v-if="room.lastError && !endError"
        class="interview-page__alert"
        type="error"
        :closable="false"
        show-icon
        data-testid="interview-command-error"
      >
        <template #title>指令失败（{{ room.lastError.code }}）</template>
        <p class="interview-page__failure-message">{{ room.lastError.message }}</p>
      </ElAlert>

      <section
        v-if="gateVisible"
        class="interview-page__gate"
        data-testid="interview-gate"
      >
        <h2 class="interview-page__gate-title">开始面试</h2>
        <p class="interview-page__gate-text">
          本次面试需要录制摄像头和麦克风两路媒体。点击“开始面试”后，
          浏览器会请求摄像头与麦克风权限，请允许后进入面试。
        </p>
        <p class="interview-page__gate-text">
          录制一旦开始，请勿刷新或关闭页面，否则本次录制将丢失。
        </p>
        <ElButton
          type="primary"
          data-testid="start-interview"
          :loading="roomStarting"
          @click="startRoom"
        >
          开始面试
        </ElButton>
      </section>

      <template v-else>
        <div class="interview-page__body">
          <section class="interview-page__stage" aria-label="面试题目">
            <QuestionPanel :question="question" />
            <CameraPreview :stream="media?.camera ?? null" />
            <p v-if="ending" class="interview-page__ending" data-testid="interview-ending">
              正在结束面试，等待录制上传完成…
            </p>
          </section>

          <aside class="interview-page__sidebar">
            <InterviewTimer :remaining-ms="remainingMs" />
          </aside>
        </div>

        <footer class="interview-page__controls" role="toolbar" aria-label="面试控制">
          <button
            type="button"
            class="interview-page__button"
            data-testid="next-question"
            :disabled="nextDisabled"
            @click="nextQuestion"
          >
            下一题
          </button>
          <button
            type="button"
            class="interview-page__button interview-page__button--danger"
            data-testid="end-interview"
            :disabled="baseDisabled"
            @click="endInterview"
          >
            {{ ending ? '结束中…' : '结束面试' }}
          </button>
        </footer>
      </template>
    </div>
  </ImmersiveLayout>
</template>

<style scoped>
.interview-page {
  display: flex;
  min-height: 0;
  flex: 1;
  flex-direction: column;
  gap: 12px;
  padding: 14px;
}

.interview-page__alert {
  flex: none;
}

.interview-page__failure-message {
  margin: 4px 0 0;
}

.interview-page__gate {
  display: flex;
  max-width: 600px;
  margin: auto;
  flex-direction: column;
  align-items: flex-start;
  gap: 12px;
  padding: 28px;
  border: 1px solid rgba(148, 163, 184, 0.24);
  border-radius: 16px;
  background: rgba(15, 23, 42, 0.86);
}

.interview-page__gate-title {
  margin: 0;
  font-size: 20px;
}

.interview-page__gate-text {
  margin: 0;
  color: #cbd5e1;
  line-height: 1.6;
}

.interview-page__body {
  display: grid;
  min-height: 0;
  flex: 1;
  grid-template-columns: minmax(0, 1fr) 200px;
  gap: 12px;
}

.interview-page__stage {
  position: relative;
  display: flex;
  min-height: 0;
}

.interview-page__ending {
  position: absolute;
  left: 50%;
  bottom: 18px;
  margin: 0;
  padding: 6px 14px;
  transform: translateX(-50%);
  border-radius: 999px;
  background: rgba(2, 6, 23, 0.8);
  color: #cbd5e1;
  font-size: 13px;
}

.interview-page__sidebar {
  display: flex;
  min-height: 0;
  flex-direction: column;
  gap: 12px;
}

.interview-page__controls {
  display: flex;
  flex: none;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border: 1px solid rgba(148, 163, 184, 0.2);
  border-radius: 12px;
  background: rgba(15, 23, 42, 0.86);
}

.interview-page__button {
  padding: 6px 14px;
  border: 1px solid rgba(148, 163, 184, 0.3);
  border-radius: 8px;
  background: rgba(148, 163, 184, 0.12);
  color: #e5e7eb;
  font-size: 13px;
  cursor: pointer;
  transition:
    background 0.15s ease,
    border-color 0.15s ease;
}

.interview-page__button:hover:not(:disabled) {
  border-color: rgba(96, 165, 250, 0.7);
  background: rgba(59, 130, 246, 0.18);
}

.interview-page__button:disabled {
  cursor: default;
  opacity: 0.45;
}

.interview-page__button--danger {
  margin-left: auto;
  border-color: rgba(248, 113, 113, 0.55);
  background: rgba(185, 28, 28, 0.24);
}

.interview-page__button--danger:hover:not(:disabled) {
  border-color: rgba(248, 113, 113, 0.9);
  background: rgba(185, 28, 28, 0.38);
}
</style>