<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElAlert, ElButton, ElCard, ElEmpty, ElTag } from 'element-plus'
import { listStudentTasks } from '../../api/tasks'
import type { StudentTaskView, TaskMode } from '../../api/tasks'
import { ApiError } from '../../types/api'

/**
 * Student task list (09 §4.1 / 05C §9.7). Minimal entry point for Task 2:
 * card summary plus a link to the task detail / interview pre-check page.
 */

const router = useRouter()

const tasks = ref<StudentTaskView[]>([])
const loading = ref(true)
const errorMessage = ref('')
const errorCode = ref('')

const MODE_LABELS: Record<TaskMode, string> = {
  free: '自由试讲',
  scenario: '情景试讲',
  interview: '结构化面试',
}

function modeLabel(mode: TaskMode): string {
  return MODE_LABELS[mode] ?? mode
}

function formatDuration(seconds: number): string {
  if (seconds % 60 === 0) {
    return `${seconds / 60} 分钟`
  }
  const minutes = Math.floor(seconds / 60)
  const rest = seconds % 60
  return minutes > 0 ? `${minutes} 分 ${rest} 秒` : `${rest} 秒`
}

function openTask(task: StudentTaskView): void {
  void router.push(`/student/tasks/${task.task_id}`)
}

async function loadTasks(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  errorCode.value = ''
  try {
    const page = await listStudentTasks(1, 20)
    tasks.value = page.items
  } catch (error) {
    if (error instanceof ApiError) {
      errorMessage.value = error.message
      errorCode.value = error.code
    } else {
      errorMessage.value = '任务列表加载失败，请重试。'
    }
  } finally {
    loading.value = false
  }
}

onMounted(loadTasks)
</script>

<template>
  <div class="task-list-page">
    <ElCard class="task-list-card">
      <template #header>
        <h1 class="task-list-title">我的任务</h1>
      </template>

      <p v-if="loading" class="task-state">加载中…</p>

      <ElAlert
        v-else-if="errorMessage"
        class="task-state"
        type="error"
        :closable="false"
        show-icon
      >
        <template #title>{{ errorMessage }}</template>
        <p v-if="errorCode" class="task-error-code">错误码：{{ errorCode }}</p>
        <ElButton size="small" @click="loadTasks">重试</ElButton>
      </ElAlert>

      <ElEmpty v-else-if="tasks.length === 0" description="暂无分配到你的任务" />

      <ul v-else class="task-list">
        <li v-for="task in tasks" :key="task.task_id" class="task-list-item">
          <ElCard shadow="never">
            <div class="task-list-item__header">
              <h2 class="task-list-item__title">{{ task.topic }}</h2>
              <ElTag type="info">{{ modeLabel(task.mode) }}</ElTag>
            </div>
            <p class="task-list-item__meta">
              <span>{{ task.course_name }}</span>
              <span>{{ formatDuration(task.duration_seconds) }}</span>
              <span>{{ task.question_count }} 题</span>
            </p>
            <p v-if="task.virtual_students.length" class="task-list-item__students">
              虚拟学生：{{ task.virtual_students.map((student) => student.name).join('、') }}
            </p>
            <ElButton type="primary" @click="openTask(task)">查看详情</ElButton>
          </ElCard>
        </li>
      </ul>
    </ElCard>
  </div>
</template>

<style scoped>
.task-list-page {
  max-width: 960px;
  margin: 0 auto;
  padding: 24px;
}

.task-list-title {
  margin: 0;
  font-size: 20px;
}

.task-list {
  display: grid;
  gap: 16px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.task-list-item__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.task-list-item__title {
  margin: 0;
  font-size: 18px;
}

.task-list-item__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin: 12px 0;
  color: #9ca3af;
}

.task-list-item__students {
  margin: 0 0 12px;
  color: #9ca3af;
}

.task-state {
  margin: 12px 0;
}

.task-error-code {
  margin: 8px 0;
  font-family: monospace;
}
</style>