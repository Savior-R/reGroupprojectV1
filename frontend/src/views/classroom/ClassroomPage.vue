<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElAlert, ElMessageBox } from 'element-plus'
import ImmersiveLayout from '../../layouts/ImmersiveLayout.vue'
import CameraPreview from '../../components/classroom/CameraPreview.vue'
import ClassroomControls from '../../components/classroom/ClassroomControls.vue'
import RecordingStatus from '../../components/classroom/RecordingStatus.vue'
import ScreenShare from '../../components/classroom/ScreenShare.vue'
import VirtualStudentGrid from '../../components/classroom/VirtualStudentGrid.vue'
import WsStatus from '../../components/classroom/WsStatus.vue'
import { apiClient } from '../../api/client'
import { recordingsApi } from '../../api/recordings'
import { getSession } from '../../api/sessions'
import type { ClientCommandType, EventPayload } from '../../types/event'
import type { StreamType } from '../../types/recording'
import type { ServerEvent, SessionStatus, SessionView } from '../../types/session'
import { classroomReducer, createInitialClassroomState } from '../../utils/classroomReducer'
import type { ClassroomState } from '../../utils/classroomReducer'
import { acquireMedia, createRecorder, STREAM_MIME } from '../../utils/media'
import type { AcquiredMedia } from '../../utils/media'
import { RecorderUploader } from '../../utils/recorderUploader'
import type { UploadProgress } from '../../utils/recorderUploader'
import { createWsClient, WS_GRACE_MS } from '../../utils/wsClient'
import type { WsClient, WsStatus as WsConnectionStatus } from '../../utils/wsClient'

/**
 * Classroom room (05A §6-§8, 05B §3-§4, 09 §6.5).
 *
 * Flow: load the session, acquire camera/screen/audio, create one recorder and
 * uploader per stream, connect the control channel and report `recording.ready`
 * on the first snapshot. Multipart uploads start only after
 * `recording.ready_ack.ok`; `session.start` follows once they are running. All
 * classroom state comes from the reducer; the components only emit commands.
 */

type Correctness = 'correct' | 'partial' | 'wrong'

const STREAM_ORDER: readonly StreamType[] = ['camera', 'screen', 'audio']
const END_CONFIRM_TIMEOUT_MS = 10_000

const WS_FAILURE_MESSAGES: Record<'recording_lost' | 'reconnect_timeout' | 'connect_failed', string> = {
  recording_lost: '录制已中断，无法重连。',
  reconnect_timeout: '连接重连超时，本次试讲已中断。',
  connect_failed: '无法连接课堂服务，请检查网络后重试。',
}

const route = useRoute()
const router = useRouter()

const sessionId = computed(() => String(route.params.sessionId ?? ''))
const reviewPath = computed(() => `/student/sessions/${sessionId.value}/review`)

const session = ref<SessionView | null>(null)
const classroom = ref<ClassroomState>(createInitialClassroomState())
const wsStatus = ref<WsConnectionStatus | null>(null)
const media = shallowRef<AcquiredMedia | null>(null)
const progress = ref<Partial<Record<StreamType, UploadProgress>>>({})
const selectedStudentId = ref<string | null>(null)
const loadError = ref('')
const failure = ref<string | null>(null)
const endError = ref<string | null>(null)
const ending = ref(false)
/** Loaded an `in_progress` session: no local recorders exist for it. */
const resumeLostMode = ref(false)

const uploaders = new Map<StreamType, RecorderUploader>()
let ws: WsClient | null = null
let disposed = false
let recordingLost = false
let readySent = false
let resumeSent = false
let startRequested = false
let endCommandPending = false
let finishStarted = false
let endWatchdog: ReturnType<typeof setTimeout> | null = null
let uploadsPromise: Promise<boolean> | null = null
let connectionWaiter: ((connected: boolean) => void) | null = null
let connectionTimeout: ReturnType<typeof setTimeout> | null = null

const title = computed(() => {
  const topic = classroom.value.publicConfig.topic
  return typeof topic === 'string' && topic.trim() ? topic : '课堂试讲'
})

const students = computed(() =>
  Object.values(classroom.value.students).sort((left, right) => {
    const leftOrder = left.seat_no ?? left.sort_order ?? Number.MAX_SAFE_INTEGER
    const rightOrder = right.seat_no ?? right.sort_order ?? Number.MAX_SAFE_INTEGER
    if (leftOrder !== rightOrder) {
      return leftOrder - rightOrder
    }
    return left.name.localeCompare(right.name)
  }),
)

const selectedStudent = computed(() => {
  const id = selectedStudentId.value
  return id ? (classroom.value.students[id] ?? null) : null
})

const answerId = computed(() => {
  const activity = classroom.value.lastStudentActivity
  if (!activity || (activity.type !== 'student.answer' && activity.type !== 'student.follow_up')) {
    return null
  }
  return activity.answerId
})

const dialogueActive = computed(() => classroom.value.dialogue !== null)
const controlsFailed = computed(
  () => failure.value !== null || loadError.value !== '' || resumeLostMode.value,
)

function isTerminal(status: SessionStatus): boolean {
  return status === 'ended' || status === 'reviewing' || status === 'submitted'
}

function messageOf(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback
}

async function fetchTicket(): Promise<string> {
  const response = await apiClient.post<{ ticket: string }>(
    `/sessions/${encodeURIComponent(sessionId.value)}/ws-ticket`,
  )
  return response.data.ticket
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
  addRecorder('camera', acquired.camera)
  if (acquired.screen) {
    addRecorder('screen', acquired.screen)
  }
  addRecorder('audio', acquired.audio)
}

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
  if (classroom.value.lastError) {
    classroom.value = { ...classroom.value, lastError: null }
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
  if (!connected || !sendCommand('session.start')) {
    handleRecordingFailure('无法开始试讲：与课堂服务的连接已断开。')
  }
}

function handleSnapshot(): void {
  if (readySent || classroom.value.status !== 'preparing' || uploaders.size === 0) {
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
      '结束试讲超时：服务器未确认结束，录制已停止并继续上传，请检查网络后重试。'
    ending.value = false
    media.value?.stop()
  }, END_CONFIRM_TIMEOUT_MS)
}

/** Starts the single upload-and-navigate finish flow once `session.ended` arrives. */
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
  classroom.value = classroomReducer(classroom.value, event)

  switch (event.type) {
    case 'session.snapshot':
      handleSnapshot()
      return

    case 'recording.ready_ack':
      if (event.payload.ok === true) {
        void beginRecordingStart()
      } else {
        handleRecordingFailure('录制流未全部就绪，无法开始试讲。')
      }
      return

    case 'session.ended':
      ws?.close()
      ensureFinish()
      return

    case 'session.failed': {
      // A failed session is terminal; release the end flow as well so an
      // in-flight “结束试讲” cannot wait forever.
      endCommandPending = false
      clearEndWatchdog()
      const code =
        typeof event.payload.error_code === 'string' ? `（${event.payload.error_code}）` : ''
      handleRecordingFailure(`会话失败${code}，本次试讲无法继续。`)
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
        endError.value = `结束试讲失败（${code}）：${message}`
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
  for (const uploader of uploaders.values()) {
    void uploader.abort()
  }
  media.value?.stop()
  ws?.close()
}

function stopAllUploaders(): Promise<boolean> {
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
  // Task 7 registers this route; until then the literal path resolves through
  // the catch-all 404 page, which is expected for this slice.
  await router.push(reviewPath.value)
}

async function endSession(): Promise<void> {
  if (ending.value || failure.value || classroom.value.status !== 'in_progress') {
    return
  }
  ending.value = true
  endError.value = null

  // Stop the recorders first, then ask the server to end; the final parts keep
  // uploading over REST while the control channel delivers `session.ended`.
  stopAllUploaders()

  // A reconnect may be in progress: wait inside the 30 s grace instead of
  // aborting the recording data. The uploads keep running meanwhile.
  const connected = await waitForConnection(WS_GRACE_MS)
  if (disposed) {
    return
  }
  if (finishStarted || classroom.value.status !== 'in_progress') {
    // `session.ended` already arrived (for example a server timeout) and the
    // finish flow owns the remaining work.
    return
  }
  if (failure.value) {
    ending.value = false
    return
  }
  if (!connected) {
    endError.value =
      '无法连接课堂服务，结束指令未发送；录制已停止并继续上传，请恢复网络后重试。'
    ending.value = false
    media.value?.stop()
    return
  }

  if (!sendCommand('teacher.end_session')) {
    endError.value = '结束指令发送失败；录制已停止并继续上传，请重试。'
    ending.value = false
    media.value?.stop()
    return
  }
  endCommandPending = true
  armEndWatchdog()
}

function selectStudent(studentId: string): void {
  selectedStudentId.value = studentId
}

function askClass(): void {
  sendCommand('teacher.ask_class')
}

function askStudentQuestion(): void {
  sendCommand('teacher.ask_student_question')
}

function callOn(): void {
  const student = selectedStudent.value
  if (!student) {
    return
  }
  const payload: EventPayload = { student_id: student.virtual_student_id }
  if (student.hand_raise_id) {
    payload.hand_raise_id = student.hand_raise_id
  }
  sendCommand('teacher.call_on', payload)
}

function remind(): void {
  const student = selectedStudent.value
  if (!student) {
    return
  }
  sendCommand('teacher.manage_student', {
    student_id: student.virtual_student_id,
    action: 'remind',
  })
}

function mark(correctness: Correctness): void {
  const id = answerId.value
  if (!id) {
    return
  }
  sendCommand('teacher.mark_answer', { answer_id: id, correctness })
}

function endDialogue(): void {
  const dialogue = classroom.value.dialogue
  if (!dialogue) {
    return
  }
  sendCommand('teacher.end_dialogue', { dialogue_id: dialogue.dialogueId })
}

function endQa(): void {
  sendCommand('teacher.end_qa')
}

async function confirmExit(): Promise<void> {
  if (ending.value) {
    return
  }
  if (failure.value || endError.value || classroom.value.status !== 'in_progress') {
    await router.push('/student/tasks')
    return
  }
  try {
    await ElMessageBox.confirm('结束本次试讲并上传录制吗？', '结束试讲', {
      confirmButtonText: '结束并上传',
      cancelButtonText: '继续试讲',
      type: 'warning',
    })
  } catch {
    return
  }
  await endSession()
}

function handleBack(): void {
  void confirmExit()
}

function connectControlChannel(): void {
  ws = createWsClient({
    sessionId: sessionId.value,
    ticketProvider: fetchTicket,
    onEvent: handleServerEvent,
    onStatus: handleConnectionStatus,
    recordingState: () =>
      recordingLost || resumeLostMode.value ? 'lost' : 'active',
  })
  void ws.connect()
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
    return
  }

  if (isTerminal(session.value.status)) {
    // `session.ended` normally navigates here; a refresh on an already ended
    // session goes straight to the review room (Task 7 registers it).
    await router.replace(reviewPath.value)
    return
  }
  if (session.value.status === 'failed' || session.value.status === 'cancelled') {
    failure.value = '该会话已结束，无法继续试讲。'
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

  try {
    media.value = await acquireMedia(session.value.mode)
  } catch (error) {
    failure.value = messageOf(
      error,
      '无法获取摄像头、麦克风或屏幕共享，请检查设备权限后重试。',
    )
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

onMounted(() => {
  void init()
})

onBeforeUnmount(() => {
  disposed = true
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
      <RecordingStatus :streams="progress" />
    </template>

    <div class="classroom-page">
      <ElAlert
        v-if="loadError"
        class="classroom-page__alert"
        type="error"
        :closable="false"
        show-icon
        data-testid="classroom-load-error"
      >
        <template #title>{{ loadError }}</template>
      </ElAlert>

      <ElAlert
        v-if="failure"
        class="classroom-page__alert"
        type="error"
        :closable="false"
        show-icon
        data-testid="classroom-failure"
      >
        <template #title>录制或会话失败</template>
        <p class="classroom-page__failure-message">{{ failure }}</p>
      </ElAlert>

      <ElAlert
        v-if="endError"
        class="classroom-page__alert"
        type="warning"
        :closable="false"
        show-icon
        data-testid="classroom-end-error"
      >
        <template #title>结束试讲未完成</template>
        <p class="classroom-page__failure-message">{{ endError }}</p>
      </ElAlert>

      <ElAlert
        v-if="classroom.lastError && !endError"
        class="classroom-page__alert"
        type="error"
        :closable="false"
        show-icon
        data-testid="classroom-command-error"
      >
        <template #title>指令失败（{{ classroom.lastError.code }}）</template>
        <p class="classroom-page__failure-message">{{ classroom.lastError.message }}</p>
      </ElAlert>

      <div class="classroom-page__body">
        <section class="classroom-page__stage" aria-label="共享屏幕">
          <ScreenShare :stream="media?.screen ?? null" />
          <CameraPreview :stream="media?.camera ?? null" />
          <p v-if="ending" class="classroom-page__ending" data-testid="classroom-ending">
            正在结束试讲，等待录制上传完成…
          </p>
        </section>

        <aside class="classroom-page__sidebar">
          <VirtualStudentGrid
            :students="students"
            :selected-id="selectedStudentId"
            :disabled="controlsFailed || classroom.status !== 'in_progress'"
            @select="selectStudent"
          />
        </aside>
      </div>

      <footer class="classroom-page__controls">
        <ClassroomControls
          :status="classroom.status"
          :dialogue-active="dialogueActive"
          :answer-id="answerId"
          :selected-student-id="selectedStudentId"
          :failed="controlsFailed"
          :ending="ending"
          @ask-class="askClass"
          @ask-student-question="askStudentQuestion"
          @call-on="callOn"
          @mark="mark"
          @remind="remind"
          @end-dialogue="endDialogue"
          @end-qa="endQa"
          @end-session="endSession"
        />
      </footer>
    </div>
  </ImmersiveLayout>
</template>

<style scoped>
.classroom-page {
  display: flex;
  min-height: 0;
  flex: 1;
  flex-direction: column;
  gap: 12px;
  padding: 14px;
}

.classroom-page__alert {
  flex: none;
}

.classroom-page__failure-message {
  margin: 4px 0 0;
}

.classroom-page__body {
  display: grid;
  min-height: 0;
  flex: 1;
  grid-template-columns: minmax(0, 1fr) 280px;
  gap: 12px;
}

.classroom-page__stage {
  position: relative;
  min-height: 0;
}

.classroom-page__ending {
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

.classroom-page__sidebar {
  display: flex;
  min-height: 0;
  flex-direction: column;
}

.classroom-page__sidebar > * {
  flex: 1;
}

.classroom-page__controls {
  flex: none;
}
</style>