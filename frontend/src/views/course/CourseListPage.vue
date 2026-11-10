<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ElAlert,
  ElButton,
  ElCard,
  ElEmpty,
  ElInput,
  ElMessage,
  ElMessageBox,
  ElOption,
  ElPagination,
  ElRadioButton,
  ElRadioGroup,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus'
import { useAuthStore } from '../../stores/auth'
import { ApiError } from '../../types/api'
import {
  archiveCourse,
  joinCourse,
  leaveCourse,
  listCourses,
  restoreCourse,
} from '../../api/courses'
import type { CourseCardView, CourseStatus } from '../../api/courses'
import CourseCreateDrawer from './CourseCreateDrawer.vue'

/**
 * 课程列表 / 发现（设计 03 §4.7）。
 *
 * - 学生：卡片，只看到本校、专业在课程范围内的启用课程，可加入 / 退出。
 * - 教师：表格，默认「我的课程」，可切换全校只读。
 * - 管理员：表格，含已归档，可归档 / 恢复，不可创建课程。
 */

const router = useRouter()
const auth = useAuthStore()

const isStudent = computed(() => auth.accountType === 'STUDENT')
const isTeacher = computed(() => auth.accountType === 'TEACHER')
const isAdmin = computed(() => auth.accountType === 'ADMIN')

const courses = ref<CourseCardView[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')
const scope = ref<'mine' | 'all'>('mine')
const status = ref<'' | CourseStatus | 'ended'>('')
const loading = ref(false)
const errorMessage = ref('')
const createVisible = ref(false)

const STATUS_LABELS: Record<string, string> = {
  active: '启用',
  archived: '已归档',
}

function statusLabel(course: CourseCardView): string {
  if (course.status === 'archived') {
    return '已归档'
  }
  return course.ended ? '已结束' : '启用中'
}

function statusTagType(course: CourseCardView): 'success' | 'warning' | 'info' {
  if (course.status === 'archived') {
    return 'info'
  }
  return course.ended ? 'warning' : 'success'
}

function teacherNames(course: CourseCardView): string {
  return course.teachers.map((teacher) => teacher.username || teacher.account_id).join('、') || '—'
}

function majorNames(course: CourseCardView): string {
  return course.majors.map((major) => major.name || major.id).join('、') || '—'
}

function formatDate(value: string | null): string {
  return value ? new Date(value).toLocaleDateString() : '—'
}

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listCourses({
      keyword: keyword.value,
      scope: isTeacher.value ? scope.value : undefined,
      status: isAdmin.value && status.value ? status.value : undefined,
      page: page.value,
      size: size.value,
    })
    courses.value = result.items
    total.value = result.total
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? `${error.message}（${error.code}）` : '课程列表加载失败'
  } finally {
    loading.value = false
  }
}

async function join(course: CourseCardView): Promise<void> {
  try {
    await joinCourse(course.id)
    ElMessage.success('已加入课程')
    await load()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加入课程失败')
  }
}

async function leave(course: CourseCardView): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认退出课程「${course.name}」？`, '退出课程', { type: 'warning' })
  } catch {
    return
  }
  try {
    await leaveCourse(course.id)
    ElMessage.success('已退出课程')
    await load()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '退出课程失败')
  }
}

async function toggleArchive(course: CourseCardView): Promise<void> {
  const archiving = course.status !== 'archived'
  try {
    await ElMessageBox.confirm(
      archiving ? `归档后学生不可见、不可加入，确认归档「${course.name}」？` : `确认恢复课程「${course.name}」？`,
      archiving ? '归档课程' : '恢复课程',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    if (archiving) {
      await archiveCourse(course.id)
    } else {
      await restoreCourse(course.id)
    }
    ElMessage.success(archiving ? '课程已归档' : '课程已恢复')
    await load()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '操作失败')
  }
}

function openCourse(course: CourseCardView): void {
  void router.push(`/courses/${course.id}`)
}

function openMembers(course: CourseCardView): void {
  void router.push(`/courses/${course.id}/students`)
}

function onSearch(): void {
  page.value = 1
  void load()
}

function onPageChange(next: number): void {
  page.value = next
  void load()
}

function onCreated(): void {
  createVisible.value = false
  void load()
}

onMounted(load)
</script>

<template>
  <div class="course-page">
    <ElCard class="course-page__card">
      <template #header>
        <div class="course-page__header">
          <h1>课程</h1>
          <ElButton v-if="isTeacher" type="primary" @click="createVisible = true">创建课程</ElButton>
        </div>
      </template>

      <div class="course-filters">
        <ElInput
          v-model="keyword"
          class="course-filters__keyword"
          placeholder="按课程名称或教师姓名检索"
          clearable
          @keyup.enter="onSearch"
          @clear="onSearch"
        />
        <ElRadioGroup v-if="isTeacher" v-model="scope" @change="onSearch">
          <ElRadioButton value="mine">我的课程</ElRadioButton>
          <ElRadioButton value="all">全校课程</ElRadioButton>
        </ElRadioGroup>
        <ElSelect v-if="isAdmin" v-model="status" class="course-filters__status" placeholder="全部状态" clearable @change="onSearch">
          <ElOption label="启用中" value="active" />
          <ElOption label="已结束" value="ended" />
          <ElOption label="已归档" value="archived" />
        </ElSelect>
        <ElButton @click="onSearch">查询</ElButton>
      </div>

      <ElAlert v-if="errorMessage" type="error" :closable="false" show-icon :title="errorMessage" />

      <p v-else-if="loading" class="course-state">加载中…</p>

      <ElEmpty v-else-if="courses.length === 0" description="没有符合条件的课程" />

      <template v-else>
        <!-- 学生：卡片 -->
        <div v-if="isStudent" class="course-cards">
          <ElCard v-for="course in courses" :key="course.id" shadow="hover" class="course-card">
            <div class="course-card__header">
              <h2 class="course-card__title">{{ course.name }}</h2>
              <ElTag :type="statusTagType(course)" size="small">{{ statusLabel(course) }}</ElTag>
            </div>
            <p class="course-card__meta">专业：{{ majorNames(course) }}</p>
            <p class="course-card__meta">老师组：{{ teacherNames(course) }}</p>
            <p class="course-card__meta">学生人数：{{ course.student_count }} · 结束日期：{{ formatDate(course.end_at) }}</p>
            <div class="course-card__actions">
              <ElButton size="small" @click="openCourse(course)">查看详情</ElButton>
              <ElButton
                v-if="!course.joined && course.status === 'active' && !course.ended"
                size="small"
                type="primary"
                @click="join(course)"
              >
                加入课程
              </ElButton>
              <ElButton v-else-if="course.joined" size="small" @click="leave(course)">退出课程</ElButton>
            </div>
          </ElCard>
        </div>

        <!-- 教师 / 管理员：表格 -->
        <ElTable v-else :data="courses" row-key="id" stripe>
          <ElTableColumn prop="name" label="课程" min-width="180" />
          <ElTableColumn label="专业" min-width="160">
            <template #default="{ row }">{{ majorNames(row) }}</template>
          </ElTableColumn>
          <ElTableColumn label="老师组" min-width="160">
            <template #default="{ row }">{{ teacherNames(row) }}</template>
          </ElTableColumn>
          <ElTableColumn prop="student_count" label="学生数" width="90" />
          <ElTableColumn label="结束日期" width="120">
            <template #default="{ row }">{{ formatDate(row.end_at) }}</template>
          </ElTableColumn>
          <ElTableColumn label="状态" width="100">
            <template #default="{ row }">
              <ElTag :type="statusTagType(row)" size="small">{{ statusLabel(row) }}</ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="240">
            <template #default="{ row }">
              <ElButton link type="primary" @click="openCourse(row)">详情</ElButton>
              <ElButton v-if="row.can_manage" link type="primary" @click="openMembers(row)">学生</ElButton>
              <ElButton v-if="isAdmin" link type="warning" @click="toggleArchive(row)">
                {{ row.status === 'archived' ? '恢复' : '归档' }}
              </ElButton>
            </template>
          </ElTableColumn>
        </ElTable>

        <ElPagination
          class="course-pagination"
          layout="prev, pager, next, total"
          :current-page="page"
          :page-size="size"
          :total="total"
          @current-change="onPageChange"
        />
      </template>
    </ElCard>

    <CourseCreateDrawer v-model:visible="createVisible" @created="onCreated" />
  </div>
</template>

<style scoped>
.course-page {
  max-width: 1120px;
  margin: 0 auto;
  padding: 24px;
}

.course-page__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.course-page__header h1 {
  margin: 0;
  font-size: 20px;
}

.course-filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.course-filters__keyword {
  max-width: 320px;
}

.course-filters__status {
  width: 140px;
}

.course-state {
  padding: 24px 0;
  text-align: center;
  opacity: 0.7;
}

.course-cards {
  display: grid;
  gap: 16px;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
}

.course-card__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.course-card__title {
  margin: 0;
  font-size: 16px;
}

.course-card__meta {
  margin: 8px 0 0;
  font-size: 13px;
  opacity: 0.8;
}

.course-card__actions {
  display: flex;
  gap: 8px;
  margin-top: 16px;
}

.course-pagination {
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
