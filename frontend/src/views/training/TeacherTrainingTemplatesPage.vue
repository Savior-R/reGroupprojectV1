<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ElAlert,
  ElButton,
  ElCard,
  ElCheckbox,
  ElCheckboxGroup,
  ElDrawer,
  ElEmpty,
  ElMessage,
  ElMessageBox,
  ElOption,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus'
import { ApiError } from '../../types/api'
import { listCourses } from '../../api/courses'
import type { CourseCardView } from '../../api/courses'
import {
  deleteMyTemplate,
  listMyTemplates,
  listPublications,
  publishTemplate,
} from './api'
import {
  formatDateTime,
  formatSeconds,
  levelLabel,
  sourceLabel,
  templateStatusLabel,
} from './format'
import type { TrainingLevel, TrainingTemplateSummary } from './types'

/** Teacher personal template list (08 §12.9); no archive / usage / student data. */

const router = useRouter()
const templates = ref<TrainingTemplateSummary[]>([])
const loading = ref(true)
const errorMessage = ref('')
const levelFilter = ref<TrainingLevel | ''>('')

const publishVisible = ref(false)
const publishingTemplate = ref<TrainingTemplateSummary | null>(null)
const publishCourses = ref<CourseCardView[]>([])
const publishedCourseIds = ref<Set<string>>(new Set())
const selectedCourseIds = ref<string[]>([])
const publishLoading = ref(false)
const publishing = ref(false)
const publishError = ref('')

const activeCount = computed(() => templates.value.filter((item) => item.status === 'active').length)
const availableCourses = computed(() =>
  publishCourses.value.filter(
    (course) =>
      course.status === 'active' && !course.ended && course.can_manage
        && !publishedCourseIds.value.has(course.id),
  ),
)

function errorText(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback
}

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const page = await listMyTemplates({
      role: 'teacher',
      level: levelFilter.value || undefined,
      status: 'active',
      page: 1,
      size: 50,
    })
    templates.value = page.items
  } catch (error) {
    errorMessage.value = errorText(error, '模板列表加载失败，请重试。')
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  void router.push({ name: 'teacher-training-template-new' })
}

function openDetail(template: TrainingTemplateSummary): void {
  void router.push({
    name: 'teacher-training-template-detail',
    params: { templateId: template.template_id },
  })
}

async function removeTemplate(template: TrainingTemplateSummary): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '已发布课程会先下架；模板关联训练记录、录像、报告与参考材料会清理；学生副本与关联任务快照不受影响；删除不可恢复。',
      `删除「${template.name}」`,
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await deleteMyTemplate(template.template_id)
    ElMessage.success('模板已删除')
    await load()
  } catch (error) {
    errorMessage.value = errorText(error, '删除失败，请重试。')
  }
}

async function openPublish(template: TrainingTemplateSummary): Promise<void> {
  publishingTemplate.value = template
  publishVisible.value = true
  publishError.value = ''
  selectedCourseIds.value = []
  publishLoading.value = true
  try {
    const [coursePage, publicationPage] = await Promise.all([
      listCourses({ scope: 'mine', status: 'active', page: 1, size: 100 }),
      listPublications({ templateId: template.template_id, status: 'published', size: 100 }),
    ])
    publishCourses.value = coursePage.items
    publishedCourseIds.value = new Set(
      publicationPage.items.map((publication) => publication.course_id),
    )
  } catch (error) {
    publishError.value = errorText(error, '课程列表加载失败。')
  } finally {
    publishLoading.value = false
  }
}

async function publishSelected(): Promise<void> {
  const template = publishingTemplate.value
  if (!template) {
    return
  }
  if (selectedCourseIds.value.length === 0) {
    publishError.value = '请选择至少一个目标课程。'
    return
  }
  publishing.value = true
  publishError.value = ''
  const failed: string[] = []
  for (const courseId of selectedCourseIds.value) {
    try {
      await publishTemplate(template.template_id, courseId)
    } catch (error) {
      failed.push(errorText(error, '发布失败'))
    }
  }
  publishing.value = false
  if (failed.length > 0) {
    publishError.value = failed.join('；')
    return
  }
  ElMessage.success('模板已发布')
  publishVisible.value = false
  await load()
}

onMounted(load)
</script>

<template>
  <div class="teacher-page">
    <header class="teacher-page__header">
      <div>
        <h1>训练模板</h1>
        <p>个人模板库；发布到目标课程后，学生可在训练首页看到。最多保留 30 个使用中的模板。</p>
      </div>
      <div class="teacher-page__actions">
        <ElSelect v-model="levelFilter" placeholder="全部档位" clearable @change="load">
          <ElOption label="进阶" value="progressive" />
          <ElOption label="模拟考核" value="mock_exam" />
        </ElSelect>
        <ElButton type="primary" @click="openCreate">新建模板</ElButton>
      </div>
    </header>

    <ElAlert v-if="errorMessage" type="error" :closable="false" show-icon>
      <template #title>{{ errorMessage }}</template>
    </ElAlert>

    <ElAlert type="info" :closable="false" show-icon>
      目标课程归档、你被移出课程或模板被删除时，发布会自动下架；已选中训练记录保留至到期。
    </ElAlert>

    <div class="quota">使用中的模板：{{ activeCount }} / 30</div>

    <p v-if="loading" class="state">加载中…</p>
    <ElEmpty v-else-if="templates.length === 0" description="还没有训练模板">
      <ElButton type="primary" plain @click="openCreate">从零创建</ElButton>
    </ElEmpty>

    <ElCard v-else shadow="never">
      <ElTable :data="templates" stripe>
        <ElTableColumn label="名称 / 课题" min-width="240">
          <template #default="{ row }">
            <strong>{{ row.name }}</strong>
            <p class="cell-sub">{{ row.topic }}</p>
          </template>
        </ElTableColumn>
        <ElTableColumn label="档位" width="110">
          <template #default="{ row }">
            <ElTag effect="plain">{{ levelLabel(row.training_level) }}</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="来源" width="110">
          <template #default="{ row }">
            <span class="cell-sub">{{ sourceLabel(row.source_type) }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="配置" min-width="190">
          <template #default="{ row }">
            <p class="cell-sub">
              {{ formatSeconds(row.duration_seconds) }} · {{ row.virtual_student_count }} 名学生
            </p>
          </template>
        </ElTableColumn>
        <ElTableColumn label="发布课程" width="100">
          <template #default="{ row }">
            <ElTag :type="row.published_course_count > 0 ? 'success' : 'info'">
              {{ row.published_course_count }} 个
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="100">
          <template #default="{ row }">
            <ElTag :type="row.status === 'active' ? 'success' : 'info'">
              {{ templateStatusLabel(row.status) }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="更新时间" width="170">
          <template #default="{ row }">
            <span class="cell-sub">{{ formatDateTime(row.updated_at) }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <ElButton size="small" text @click="openDetail(row as TrainingTemplateSummary)">
              查看 / 编辑
            </ElButton>
            <ElButton
              size="small"
              text
              @click="openPublish(row as TrainingTemplateSummary)"
            >
              发布
            </ElButton>
            <ElButton
              size="small"
              text
              type="danger"
              @click="removeTemplate(row as TrainingTemplateSummary)"
            >
              删除
            </ElButton>
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>

    <ElDrawer
      v-model="publishVisible"
      :title="`发布「${publishingTemplate?.name ?? ''}」`"
      size="420px"
    >
      <p class="drawer-hint">
        {{ levelLabel(publishingTemplate?.training_level ?? 'progressive') }} ·
        {{ publishingTemplate?.virtual_student_count ?? 0 }} 名学生；发布使用当前配置快照。
      </p>
      <ElAlert v-if="publishError" type="error" :closable="false" show-icon>
        <template #title>{{ publishError }}</template>
      </ElAlert>
      <p v-if="publishLoading" class="state">课程加载中…</p>
      <ElEmpty
        v-else-if="availableCourses.length === 0"
        description="没有可发布的目标课程；已发布的课程不会重复出现。"
        :image-size="72"
      />
      <ElCheckboxGroup v-else v-model="selectedCourseIds" class="course-options">
        <label v-for="course in availableCourses" :key="course.id" class="course-option">
          <ElCheckbox :value="course.id">
            <span class="course-option__name">{{ course.name }}</span>
            <span class="course-option__meta">{{ course.student_count }} 名学生</span>
          </ElCheckbox>
        </label>
      </ElCheckboxGroup>
      <p class="drawer-hint">
        发布后学生可在训练首页看到并复制；模板配置锁定，需先下架再编辑。目标课程归档、你被移出课程或模板被删除时，发布会自动下架。
      </p>
      <template #footer>
        <ElButton @click="publishVisible = false">取消</ElButton>
        <ElButton
          type="primary"
          :loading="publishing"
          :disabled="publishLoading || availableCourses.length === 0"
          @click="publishSelected"
        >
          发布
        </ElButton>
      </template>
    </ElDrawer>
  </div>
</template>

<style scoped>
.teacher-page {
  max-width: 1180px;
  margin: 0 auto;
  padding: 24px;
  display: grid;
  gap: 16px;
}

.teacher-page__header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
}

.teacher-page__header h1 {
  margin: 0 0 6px;
  color: var(--m1-text-primary);
  font-family: var(--m1-font-serif);
}

.teacher-page__header p {
  margin: 0;
  color: var(--m1-text-secondary);
}

.teacher-page__actions {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.quota {
  justify-self: start;
  padding: 4px 10px;
  border-radius: 999px;
  background: var(--m1-primary-soft);
  color: var(--m1-primary);
  font-size: 12px;
}

.state {
  color: var(--m1-text-secondary);
}

.cell-sub {
  margin: 2px 0 0;
  color: var(--m1-text-secondary);
  font-size: 12px;
  word-break: break-word;
}

.drawer-hint {
  margin: 0 0 14px;
  color: var(--m1-text-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.course-options {
  display: grid;
  gap: 8px;
  margin: 14px 0;
}

.course-option {
  padding: 10px 12px;
  border: 1px solid var(--m1-border);
  border-radius: 10px;
  cursor: pointer;
}

.course-option :deep(.el-checkbox) {
  width: 100%;
}

.course-option :deep(.el-checkbox__label) {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
}

.course-option__name {
  color: var(--m1-text-primary);
}

.course-option__meta {
  color: var(--m1-text-secondary);
  font-size: 12px;
}
</style>
