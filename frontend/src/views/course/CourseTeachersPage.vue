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
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus'
import { ApiError } from '../../types/api'
import { addTeacher, listTeachers, removeTeacher } from '../../api/courses'
import type { CourseTeacherView } from '../../api/courses'

/** 课程老师组：组内平权，任何在组教师或本校管理员可增删；至少保留一名有效教师。 */

const route = useRoute()
const router = useRouter()
const courseId = String(route.params.courseId ?? '')

const teachers = ref<CourseTeacherView[]>([])
const includeHistory = ref(false)
const accountId = ref('')
const loading = ref(false)
const errorMessage = ref('')

function formatDate(value: string | null): string {
  return value ? new Date(value).toLocaleString() : '—'
}

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    teachers.value = await listTeachers(courseId, includeHistory.value)
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? `${error.message}（${error.code}）` : '老师组加载失败'
  } finally {
    loading.value = false
  }
}

async function add(): Promise<void> {
  const value = accountId.value.trim()
  if (!value) {
    ElMessage.warning('请填写教师账号 ID')
    return
  }
  try {
    await addTeacher(courseId, value)
    ElMessage.success('已加入老师组')
    accountId.value = ''
    await load()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '添加教师失败')
  }
}

async function remove(teacher: CourseTeacherView): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认将「${teacher.username || teacher.account_id}」移出老师组？移出后保留历史记录。`,
      '移出教师',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await removeTeacher(courseId, teacher.account_id)
    ElMessage.success('已移出老师组')
    await load()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '移出教师失败')
  }
}

onMounted(load)
</script>

<template>
  <div class="course-members">
    <ElCard>
      <template #header>
        <div class="course-members__header">
          <h1>老师组</h1>
          <ElButton @click="router.push(`/courses/${courseId}`)">返回课程</ElButton>
        </div>
      </template>

      <div class="course-members__toolbar">
        <ElInput v-model="accountId" placeholder="教师账号 ID（UUID）" class="course-members__input" />
        <ElButton type="primary" @click="add">添加教师</ElButton>
        <ElSwitch v-model="includeHistory" active-text="含历史成员" @change="load" />
      </div>

      <ElAlert v-if="errorMessage" type="error" :closable="false" show-icon :title="errorMessage" />
      <p v-else-if="loading">加载中…</p>

      <ElTable v-else :data="teachers" row-key="account_id" stripe>
        <ElTableColumn prop="username" label="教师" min-width="160">
          <template #default="{ row }">{{ row.username || row.account_id }}</template>
        </ElTableColumn>
        <ElTableColumn prop="account_id" label="账号 ID" min-width="260" />
        <ElTableColumn label="状态" width="100">
          <template #default="{ row }">
            <ElTag :type="row.status === 'active' ? 'success' : 'info'" size="small">
              {{ row.status === 'active' ? '在组' : '已移出' }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="加入时间" width="180">
          <template #default="{ row }">{{ formatDate(row.joined_at) }}</template>
        </ElTableColumn>
        <ElTableColumn label="移出时间" width="180">
          <template #default="{ row }">{{ formatDate(row.left_at) }}</template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="120">
          <template #default="{ row }">
            <ElButton v-if="row.status === 'active'" link type="danger" @click="remove(row as CourseTeacherView)">移出</ElButton>
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>
  </div>
</template>

<style scoped>
.course-members {
  max-width: 1040px;
  margin: 0 auto;
  padding: 24px;
}

.course-members__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.course-members__header h1 {
  margin: 0;
  font-size: 20px;
}

.course-members__toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.course-members__input {
  max-width: 320px;
}
</style>
