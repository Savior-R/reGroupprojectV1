<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton, ElCard, ElTag } from 'element-plus'
import { getStudentTask } from '../../api/tasks'
import type { AssignmentStatus, StudentTaskDetailView, TaskMode, TaskStatus } from '../../api/tasks'
import { createSession } from '../../api/sessions'
import type { SessionView } from '../../types/session'
import { ApiError } from '../../types/api'
import { isSupportedBrowser } from '../../utils/media'

/**
 * Student task detail / interview pre-check (09 §4.1, 05C §9.8).
 *
 * Shows only the public task projection: topic, mode, duration, question count
 * and virtual-student names. Weights, material and answer points are never
 * requested nor rendered.
 */

const route = useRoute()
const router = useRouter()

const taskId = computed(() => String(route.params.taskId ?? ''))
const task = ref<StudentTaskDetailView | null>(null)
const loading = ref(true)
const starting = ref(false)
const loadError = ref('')
const loadErrorCode = ref('')
const startError = ref('')
const startErrorCode = ref('')

const isInterview = computed(() => task.value?.mode === 'interview')

const browserSupported = isSupportedBrowser(navigator.userAgent)

const submitted = computed(() => task.value?.assignment_status === 'submitted')
const closed = computed(() => task.value?.status === 'closed')

/** Why the start action is unavailable, or an empty string when it is allowed. */
const startBlockedReason = computed(() => {
  if (submitted.value) {
    return '该任务已提交，不能再次开始。'
  }
  if (closed.value) {
    return '任务已关闭，无法开始试讲。'
  }
  if (!browserSupported) {
    return '当前浏览器不支持录制，请使用 Chrome 或 Edge。'
  }
  return ''
})

const startBlocked = computed(() => startBlockedReason.value !== '')

const MODE_LABELS: Record<TaskMode, string> = {
  free: '自由试讲',
  scenario: '情景试讲',
  interview: '结构化面试',
}

const STATUS_LABELS: Record<TaskStatus, string> = {
  draft: '草稿',
  published: '已发布',
  closed: '已关闭',
}

const ASSIGNMENT_LABELS: Record<AssignmentStatus, string> = {
  assigned: '待开始',
  submitted: '已提交',
}

function modeLabel(mode: TaskMode): string {
  return MODE_LABELS[mode] ?? mode
}

function statusLabel(status: TaskStatus): string {
  return STATUS_LABELS[status] ?? status
}

function assignmentLabel(status: AssignmentStatus): string {
  return ASSIGNMENT_LABELS[status] ?? status
}

function formatDuration(seconds: number): string {
  if (seconds % 60 === 0) {
    return `${seconds / 60} 分钟`
  }
  const minutes = Math.floor(seconds / 60)
  const rest = seconds % 60
  return minutes > 0 ? `${minutes} 分 ${rest} 秒` : `${rest} 秒`
}

async function loadTask(): Promise<void> {
  loading.value = true
  loadError.value = ''
  loadErrorCode.value = ''
  try {
    task.value = await getStudentTask(taskId.value)
  } catch (error) {
    if (error instanceof ApiError) {
      loadError.value = error.message
      loadErrorCode.value = error.code
    } else {
      loadError.value = '任务加载失败，请重试。'
    }
  } finally {
    loading.value = false
  }
}

function sessionPath(session: SessionView): string {
  return session.mode === 'interview'
    ? `/student/sessions/${session.session_id}/interview`
    : `/student/sessions/${session.session_id}/classroom`
}

async function startTask(): Promise<void> {
  if (starting.value || startBlocked.value) {
    return
  }
  starting.value = true
  startError.value = ''
  startErrorCode.value = ''
  try {
    const session = await createSession(taskId.value)
    await router.push(sessionPath(session))
  } catch (error) {
    if (error instanceof ApiError) {
      startErrorCode.value = error.code
      // The student list/detail payload does not expose the active session id,
      // so do not guess a resume path. Point the student at the task list (where
      // an active attempt can be cancelled/resumed) or the teacher.
      startError.value =
        error.code === 'SESSION_ACTIVE_EXISTS'
          ? '已有进行中的会话。请从任务列表取消或继续该会话；如无法处理，请联系教师。'
          : error.message
    } else {
      startError.value = '创建会话失败，请重试。'
    }
  } finally {
    starting.value = false
  }
}

onMounted(loadTask)
</script>

<template>
  <div class="task-detail-page">
    <ElCard class="task-detail-card">
      <template #header>
        <div class="task-detail-header">
          <div>
            <h1 class="task-detail-title">{{ task?.topic ?? '任务详情' }}</h1>
            <p v-if="task" class="task-detail-subtitle">{{ task.course_name }}</p>
          </div>
          <ElTag v-if="task" type="info">{{ modeLabel(task.mode) }}</ElTag>
        </div>
      </template>

      <p v-if="loading" class="task-state">加载中…</p>

      <ElAlert
        v-else-if="loadError"
        class="task-state"
        type="error"
        :closable="false"
        show-icon
      >
        <template #title>{{ loadError }}</template>
        <p v-if="loadErrorCode" class="task-error-code">错误码：{{ loadErrorCode }}</p>
        <ElButton size="small" @click="loadTask">重试</ElButton>
      </ElAlert>

      <template v-else-if="task">
        <dl class="task-meta">
          <div class="task-meta__item">
            <dt>课程</dt>
            <dd>{{ task.course_name }}</dd>
          </div>
          <div class="task-meta__item">
            <dt>模式</dt>
            <dd>{{ modeLabel(task.mode) }}</dd>
          </div>
          <div class="task-meta__item">
            <dt>时长</dt>
            <dd>{{ formatDuration(task.duration_seconds) }}</dd>
          </div>
          <div class="task-meta__item">
            <dt>题目数</dt>
            <dd>{{ task.question_count }} 题</dd>
          </div>
          <div class="task-meta__item">
            <dt>任务状态</dt>
            <dd>{{ statusLabel(task.status) }}</dd>
          </div>
          <div class="task-meta__item">
            <dt>完成状态</dt>
            <dd>{{ assignmentLabel(task.assignment_status) }}</dd>
          </div>
        </dl>

        <section v-if="task.virtual_students.length" class="task-section">
          <h2 class="task-section-title">虚拟学生</h2>
          <ul class="task-students">
            <li
              v-for="student in task.virtual_students"
              :key="student.virtual_student_id"
              class="task-student"
            >
              {{ student.name }}
            </li>
          </ul>
        </section>

        <ElAlert
          v-if="isInterview"
          class="task-interview-check"
          type="info"
          :closable="false"
          show-icon
        >
          <template #title>面试前检查</template>
          <p>2 路录制（摄像头 + 麦克风）</p>
          <p>共 {{ task.question_count }} 题，题目逐题展示。</p>
        </ElAlert>

        <ElAlert
          v-if="startError"
          class="task-start-error"
          type="error"
          :closable="false"
          show-icon
        >
          <template #title>{{ startError }}</template>
          <p v-if="startErrorCode" class="task-error-code">错误码：{{ startErrorCode }}</p>
          <ElButton size="small" data-testid="retry-start" @click="startTask">重试</ElButton>
        </ElAlert>

        <ElAlert
          v-if="startBlocked"
          class="task-start-blocked"
          type="warning"
          :closable="false"
          show-icon
          data-testid="start-blocked"
        >
          <template #title>{{ startBlockedReason }}</template>
        </ElAlert>

        <div class="task-actions">
          <ElButton
            type="primary"
            data-testid="start-task-session"
            :loading="starting"
            :disabled="startBlocked"
            @click="startTask"
          >
            开始试讲
          </ElButton>
        </div>
      </template>
    </ElCard>
  </div>
</template>

<style scoped>
.task-detail-page {
  max-width: 960px;
  margin: 0 auto;
  padding: 24px;
}

.task-detail-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.task-detail-title {
  margin: 0;
  font-size: 20px;
}

.task-detail-subtitle {
  margin: 4px 0 0;
  color: #9ca3af;
}

.task-meta {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 16px;
  margin: 0 0 24px;
}

.task-meta__item dt {
  color: #9ca3af;
  font-size: 12px;
}

.task-meta__item dd {
  margin: 4px 0 0;
  font-size: 16px;
}

.task-section {
  margin-bottom: 24px;
}

.task-section-title {
  margin: 0 0 8px;
  font-size: 16px;
}

.task-students {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.task-student {
  padding: 4px 10px;
  border-radius: 999px;
  background: rgba(148, 163, 184, 0.16);
}

.task-interview-check,
.task-start-error,
.task-start-blocked {
  margin-bottom: 16px;
}

.task-state {
  margin: 12px 0;
}

.task-error-code {
  margin: 8px 0;
  font-family: monospace;
}

.task-actions {
  display: flex;
  justify-content: flex-end;
}
</style>