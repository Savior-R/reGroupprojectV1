<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ElAlert,
  ElButton,
  ElCard,
  ElInput,
  ElMessage,
  ElMessageBox,
  ElPagination,
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus'
import { ApiError } from '../../types/api'
import {
  addStudentByAccount,
  addStudentsByClass,
  listStudents,
  removeStudent,
} from '../../api/courses'
import type { BatchAddStudentsResult, CourseStudentView } from '../../api/courses'

/** 课程学生名单：按班级批量加人（允许部分成功）、按账号单个加人、移出学生、查看历史。 */

const route = useRoute()
const router = useRouter()
const courseId = String(route.params.courseId ?? '')

const students = ref<CourseStudentView[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const includeHistory = ref(false)
const classId = ref('')
const studentAccountId = ref('')
const loading = ref(false)
const errorMessage = ref('')
const batchResult = ref<BatchAddStudentsResult | null>(null)

const METHOD_LABELS: Record<CourseStudentView['join_method'], string> = {
  self: '自主加入',
  class: '按班级',
  account: '按账号',
  admin: '管理员添加',
}

const STATUS_LABELS: Record<CourseStudentView['status'], string> = {
  active: '在课',
  left: '已退出',
  removed: '已移出',
}

function formatDate(value: string | null): string {
  return value ? new Date(value).toLocaleString() : '—'
}

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listStudents(courseId, {
      includeHistory: includeHistory.value,
      page: page.value,
      size: size.value,
    })
    students.value = result.items
    total.value = result.total
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? `${error.message}（${error.code}）` : '学生名单加载失败'
  } finally {
    loading.value = false
  }
}

async function addByClass(): Promise<void> {
  const value = classId.value.trim()
  if (!value) {
    ElMessage.warning('请填写班级 ID')
    return
  }
  try {
    batchResult.value = await addStudentsByClass(courseId, value)
    ElMessage.success(
      `批量加人完成：成功 ${batchResult.value.success}，跳过 ${batchResult.value.skipped}`,
    )
    classId.value = ''
    await load()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '批量加人失败')
  }
}

async function addByAccount(): Promise<void> {
  const value = studentAccountId.value.trim()
  if (!value) {
    ElMessage.warning('请填写学生账号 ID')
    return
  }
  try {
    await addStudentByAccount(courseId, value)
    ElMessage.success('已加入学生')
    studentAccountId.value = ''
    await load()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '添加学生失败')
  }
}

async function remove(student: CourseStudentView): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认将「${student.username || student.account_id}」移出课程？移出后保留历史，学生可重新加入。`,
      '移出学生',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await removeStudent(courseId, student.account_id)
    ElMessage.success('已移出学生')
    await load()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '移出学生失败')
  }
}

function onPageChange(next: number): void {
  page.value = next
  void load()
}

onMounted(load)
</script>

<template>
  <div class="course-students">
    <ElCard>
      <template #header>
        <div class="course-students__header">
          <h1>学生管理</h1>
          <ElButton @click="router.push(`/courses/${courseId}`)">返回课程</ElButton>
        </div>
      </template>

      <div class="course-students__toolbar">
        <ElInput v-model="classId" placeholder="班级 ID（UUID）批量加人" class="course-students__input" />
        <ElButton @click="addByClass">按班级加人</ElButton>
        <ElInput v-model="studentAccountId" placeholder="学生账号 ID（UUID）" class="course-students__input" />
        <ElButton @click="addByAccount">按账号加人</ElButton>
        <ElSwitch v-model="includeHistory" active-text="含历史成员" @change="load" />
      </div>

      <ElAlert
        v-if="batchResult"
        class="course-students__batch"
        type="info"
        :closable="true"
        :title="`本次共 ${batchResult.total} 人：成功 ${batchResult.success}，跳过 ${batchResult.skipped}`"
      >
        <p v-for="item in batchResult.errors" :key="item.account_id">
          {{ item.account_id }}：{{ item.reason }}
        </p>
      </ElAlert>

      <ElAlert v-if="errorMessage" type="error" :closable="false" show-icon :title="errorMessage" />
      <p v-else-if="loading">加载中…</p>

      <ElTable v-else :data="students" row-key="account_id" stripe>
        <ElTableColumn prop="username" label="学生" min-width="140">
          <template #default="{ row }">{{ row.username || row.account_id }}</template>
        </ElTableColumn>
        <ElTableColumn prop="account_id" label="账号 ID" min-width="260" />
        <ElTableColumn label="班级" min-width="140">
          <template #default="{ row }">{{ row.class_name || '—' }}</template>
        </ElTableColumn>
        <ElTableColumn label="加入方式" width="110">
          <template #default="{ row }">{{ METHOD_LABELS[row.join_method as CourseStudentView['join_method']] }}</template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="100">
          <template #default="{ row }">
            <ElTag :type="row.status === 'active' ? 'success' : 'info'" size="small">
              {{ STATUS_LABELS[row.status as CourseStudentView['status']] }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="加入时间" width="180">
          <template #default="{ row }">{{ formatDate(row.joined_at) }}</template>
        </ElTableColumn>
        <ElTableColumn label="退出时间" width="180">
          <template #default="{ row }">{{ formatDate(row.left_at) }}</template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="110">
          <template #default="{ row }">
            <ElButton v-if="row.status === 'active'" link type="danger" @click="remove(row as CourseStudentView)">移出</ElButton>
          </template>
        </ElTableColumn>
      </ElTable>

      <ElPagination
        class="course-students__pagination"
        layout="prev, pager, next, total"
        :current-page="page"
        :page-size="size"
        :total="total"
        @current-change="onPageChange"
      />
    </ElCard>
  </div>
</template>

<style scoped>
.course-students {
  max-width: 1120px;
  margin: 0 auto;
  padding: 24px;
}

.course-students__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.course-students__header h1 {
  margin: 0;
  font-size: 20px;
}

.course-students__toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.course-students__input {
  max-width: 280px;
}

.course-students__batch {
  margin-bottom: 16px;
}

.course-students__pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
