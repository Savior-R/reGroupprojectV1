<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDescriptions,
  ElDescriptionsItem,
  ElMessage,
  ElMessageBox,
  ElTag,
} from 'element-plus'
import { useAuthStore } from '../../stores/auth'
import { ApiError } from '../../types/api'
import { archiveCourse, getCourse, joinCourse, leaveCourse, restoreCourse } from '../../api/courses'
import type { CourseDetailView } from '../../api/courses'

/** 课程详情：课程信息 + 成员入口 + 归档 / 恢复（管理员）+ 加入 / 退出（学生）。 */

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const course = ref<CourseDetailView | null>(null)
const loading = ref(true)
const errorMessage = ref('')

const isAdmin = computed(() => auth.accountType === 'ADMIN')
const courseId = computed(() => String(route.params.courseId ?? ''))

function formatDate(value: string | null): string {
  return value ? new Date(value).toLocaleString() : '—'
}

function statusLabel(value: CourseDetailView): string {
  if (value.status === 'archived') {
    return '已归档'
  }
  return value.ended ? '已结束' : '启用中'
}

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    course.value = await getCourse(courseId.value)
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? `${error.message}（${error.code}）` : '课程加载失败'
  } finally {
    loading.value = false
  }
}

async function join(): Promise<void> {
  try {
    await joinCourse(courseId.value)
    ElMessage.success('已加入课程')
    await load()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加入课程失败')
  }
}

async function leave(): Promise<void> {
  try {
    await ElMessageBox.confirm('确认退出该课程？退出后可重新加入。', '退出课程', { type: 'warning' })
  } catch {
    return
  }
  try {
    await leaveCourse(courseId.value)
    ElMessage.success('已退出课程')
    await load()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '退出课程失败')
  }
}

async function toggleArchive(): Promise<void> {
  if (!course.value) {
    return
  }
  const archiving = course.value.status !== 'archived'
  try {
    await ElMessageBox.confirm(
      archiving ? '归档后学生不可见、不可加入，确认归档？' : '确认恢复该课程？',
      archiving ? '归档课程' : '恢复课程',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    if (archiving) {
      await archiveCourse(courseId.value)
    } else {
      await restoreCourse(courseId.value)
    }
    ElMessage.success(archiving ? '课程已归档' : '课程已恢复')
    await load()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '操作失败')
  }
}

onMounted(load)
</script>

<template>
  <div class="course-detail">
    <ElCard>
      <template #header>
        <div class="course-detail__header">
          <div>
            <h1>{{ course?.name ?? '课程详情' }}</h1>
            <ElTag v-if="course" size="small" :type="course.status === 'archived' ? 'info' : 'success'">
              {{ statusLabel(course) }}
            </ElTag>
          </div>
          <div class="course-detail__actions">
            <ElButton @click="router.push('/courses')">返回列表</ElButton>
            <ElButton v-if="course?.can_manage" @click="router.push(`/courses/${courseId}/teachers`)">
              老师组
            </ElButton>
            <ElButton v-if="course?.can_manage" @click="router.push(`/courses/${courseId}/students`)">
              学生管理
            </ElButton>
            <ElButton v-if="course?.can_join" type="primary" @click="join">加入课程</ElButton>
            <ElButton v-if="course?.can_leave" @click="leave">退出课程</ElButton>
            <ElButton v-if="isAdmin && course?.can_archive" type="warning" @click="toggleArchive">归档</ElButton>
            <ElButton v-if="isAdmin && course?.can_restore" type="success" @click="toggleArchive">恢复</ElButton>
          </div>
        </div>
      </template>

      <ElAlert v-if="errorMessage" type="error" :closable="false" show-icon :title="errorMessage" />
      <p v-else-if="loading">加载中…</p>

      <ElDescriptions v-else-if="course" :column="2" border>
        <ElDescriptionsItem label="专业范围">
          {{ course.majors.map((major) => major.name || major.id).join('、') || '—' }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="老师组">
          {{ course.teachers.map((teacher) => teacher.username || teacher.account_id).join('、') || '—' }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="学生人数">{{ course.student_count }}</ElDescriptionsItem>
        <ElDescriptionsItem label="结束日期">{{ formatDate(course.end_at) }}</ElDescriptionsItem>
        <ElDescriptionsItem label="创建时间">{{ formatDate(course.created_at) }}</ElDescriptionsItem>
        <ElDescriptionsItem label="归档时间">{{ formatDate(course.archived_at) }}</ElDescriptionsItem>
      </ElDescriptions>
    </ElCard>
  </div>
</template>

<style scoped>
.course-detail {
  max-width: 960px;
  margin: 0 auto;
  padding: 24px;
}

.course-detail__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.course-detail__header h1 {
  margin: 0 0 8px;
  font-size: 20px;
}

.course-detail__actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}
</style>
