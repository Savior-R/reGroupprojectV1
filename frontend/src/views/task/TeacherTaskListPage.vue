<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ElAlert,
  ElButton,
  ElCard,
  ElEmpty,
  ElMessage,
  ElMessageBox,
  ElOption,
  ElPagination,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus'
import { getCourse } from '../../api/courses'
import type { CourseDetailView } from '../../api/courses'
import {
  closeTask,
  deleteTask,
  listCourseTasks,
} from '../../api/tasks'
import type { TaskCardView, TaskMode, TaskStatus } from '../../api/tasks'
import { ApiError } from '../../types/api'

/**
 * 教师课程任务列表（05C §9.1—§9.6）。
 *
 * 课程详情 → 课程任务 → 新建 / 编辑 / 发布 / 删除 / 评分 / 统计。
 * 这是教师主线的枢纽页；路由守卫只允许 teacher / admin。
 */

const route = useRoute()
const router = useRouter()

const courseId = computed(() => String(route.params.courseId ?? ''))
const course = ref<CourseDetailView | null>(null)
const tasks = ref<TaskCardView[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const status = ref<TaskStatus | ''>('')
const mode = ref<TaskMode | ''>('')
const loading = ref(true)
const errorMessage = ref('')
const busyTaskId = ref('')

const MODE_LABELS: Record<TaskMode, string> = {
  free: '自由试讲',
  scenario: '情景试讲',
  interview: '结构化面试',
}

const STATUS_LABELS: Record<TaskStatus, string> = {
  published: '已发布',
  closed: '已关闭',
  deleted: '已删除',
}

const STATUS_TAG: Record<TaskStatus, 'info' | 'success' | 'warning'> = {
  published: 'success',
  closed: 'warning',
  deleted: 'info',
}

function errorText(error: unknown, fallback: string): string {
  return error instanceof ApiError ? `${error.message}（${error.code}）` : fallback
}

function formatTime(value: string | null): string {
  return value ? new Date(value).toLocaleString() : '—'
}

async function loadCourse(): Promise<void> {
  try {
    course.value = await getCourse(courseId.value)
  } catch (error) {
    errorMessage.value = errorText(error, '课程加载失败')
  }
}

async function loadTasks(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listCourseTasks(courseId.value, {
      status: status.value,
      mode: mode.value,
      page: page.value,
      size: size.value,
    })
    tasks.value = result.items
    total.value = result.total
  } catch (error) {
    errorMessage.value = errorText(error, '任务列表加载失败')
  } finally {
    loading.value = false
  }
}

async function reload(): Promise<void> {
  page.value = 1
  await loadTasks()
}

function openEditor(task: TaskCardView): void {
  void router.push(`/teacher/courses/${courseId.value}/tasks/${task.task_id}`)
}

function openGrading(task: TaskCardView): void {
  void router.push(
    `/teacher/courses/${courseId.value}/tasks/${task.task_id}/grading`,
  )
}

function openStats(task: TaskCardView): void {
  void router.push(
    `/teacher/courses/${courseId.value}/tasks/${task.task_id}/stats`,
  )
}

async function close(task: TaskCardView): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '关闭后学生不能再开始新的作答，已有提交仍可评分。确认关闭？',
      '关闭任务',
      { confirmButtonText: '关闭', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  busyTaskId.value = task.task_id
  try {
    await closeTask(task.task_id)
    ElMessage.success('任务已关闭')
    await loadTasks()
  } catch (error) {
    ElMessage.error(errorText(error, '关闭失败'))
  } finally {
    busyTaskId.value = ''
  }
}

async function remove(task: TaskCardView): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '删除后任务将从课程任务列表隐藏，不能开始新试讲；已提交的回放、报告、评分保留只读。确认删除？',
      '删除任务',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  busyTaskId.value = task.task_id
  try {
    await deleteTask(task.task_id)
    ElMessage.success('任务已删除')
    await loadTasks()
  } catch (error) {
    ElMessage.error(errorText(error, '删除失败'))
  } finally {
    busyTaskId.value = ''
  }
}

onMounted(async () => {
  await loadCourse()
  await loadTasks()
})
</script>

<template>
  <div class="teacher-task-page">
    <header class="teacher-task-page__header">
      <div>
        <ElButton text @click="router.push(`/courses/${courseId}`)">← 返回课程</ElButton>
        <h1>课程任务</h1>
        <p>{{ course?.name ?? '课程' }} · 创建、发布并进入评分 / 统计</p>
      </div>
      <ElButton type="primary" @click="router.push(`/teacher/courses/${courseId}/tasks/new`)">
        新建任务
      </ElButton>
    </header>

    <ElAlert v-if="errorMessage" type="error" :closable="false" show-icon>
      <template #title>{{ errorMessage }}</template>
      <ElButton size="small" @click="loadTasks">重试</ElButton>
    </ElAlert>

    <ElCard shadow="never">
      <div class="filters">
        <ElSelect v-model="status" placeholder="全部状态" clearable @change="reload">
          <ElOption label="已发布" value="published" />
          <ElOption label="已关闭" value="closed" />
        </ElSelect>
        <ElSelect v-model="mode" placeholder="全部模式" clearable @change="reload">
          <ElOption label="自由试讲" value="free" />
          <ElOption label="情景试讲" value="scenario" />
          <ElOption label="结构化面试" value="interview" />
        </ElSelect>
        <ElButton @click="reload">刷新</ElButton>
      </div>

      <p v-if="loading" class="state">加载中…</p>
      <ElEmpty v-else-if="tasks.length === 0" description="还没有任务，先新建一个任务" />

      <ElTable v-else :data="tasks" row-key="task_id" stripe>
        <ElTableColumn label="课题" min-width="240">
          <template #default="{ row }">
            <strong>{{ row.topic }}</strong>
            <p class="cell-sub">{{ MODE_LABELS[row.mode as TaskMode] }} · {{ row.duration_seconds }}s</p>
          </template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="100">
          <template #default="{ row }">
            <ElTag :type="STATUS_TAG[row.status as TaskStatus]" size="small">
              {{ STATUS_LABELS[row.status as TaskStatus] }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="配置" min-width="180">
          <template #default="{ row }">
            <span class="cell-sub">
              {{ row.mode === 'interview' ? `${row.question_count} 道题` : `${row.virtual_student_count} 名虚拟学生` }}
            </span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="分配 / 提交" width="140">
          <template #default="{ row }">
            <span>{{ row.assigned_count }} / {{ row.submitted_count }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="更新时间" width="180">
          <template #default="{ row }">{{ formatTime(row.updated_at) }}</template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <ElButton
              v-if="row.status === 'published'"
              size="small"
              @click="openEditor(row as TaskCardView)"
            >
              编辑
            </ElButton>
            <ElButton
              v-if="row.status === 'published'"
              size="small"
              :loading="busyTaskId === row.task_id"
              @click="openGrading(row as TaskCardView)"
            >
              评分
            </ElButton>
            <ElButton
              v-if="row.status === 'published'"
              size="small"
              @click="openStats(row as TaskCardView)"
            >
              统计
            </ElButton>
            <ElButton
              v-if="row.status === 'published'"
              size="small"
              :loading="busyTaskId === row.task_id"
              @click="close(row as TaskCardView)"
            >
              关闭
            </ElButton>
            <ElButton
              v-if="row.status === 'closed'"
              size="small"
              @click="openEditor(row as TaskCardView)"
            >
              详情
            </ElButton>
            <ElButton
              v-if="row.status === 'closed'"
              size="small"
              @click="openGrading(row as TaskCardView)"
            >
              评分
            </ElButton>
            <ElButton
              v-if="row.status === 'closed'"
              size="small"
              @click="openStats(row as TaskCardView)"
            >
              统计
            </ElButton>
            <ElButton
              v-if="row.status === 'published' || row.status === 'closed'"
              size="small"
              type="danger"
              plain
              :loading="busyTaskId === row.task_id"
              @click="remove(row as TaskCardView)"
            >
              删除
            </ElButton>
          </template>
        </ElTableColumn>
      </ElTable>

      <ElPagination
        v-if="total > size"
        class="pagination"
        layout="prev, pager, next, total"
        :total="total"
        :page-size="size"
        :current-page="page"
        @current-change="(value: number) => { page = value; loadTasks() }"
      />
    </ElCard>
  </div>
</template>

<style scoped>
.teacher-task-page {
  max-width: 1180px;
  margin: 0 auto;
  padding: 24px;
  display: grid;
  gap: 16px;
}

.teacher-task-page__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.teacher-task-page__header h1 {
  margin: 4px 0 4px;
  color: var(--m1-text-primary);
  font-family: var(--m1-font-serif);
  font-size: 24px;
}

.teacher-task-page__header p {
  margin: 0;
  color: var(--m1-text-secondary);
  font-size: 13px;
}

.filters {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 12px;
}

.state {
  color: var(--m1-text-secondary);
}

.cell-sub {
  margin: 2px 0 0;
  color: var(--m1-text-secondary);
  font-size: 12px;
}

.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
