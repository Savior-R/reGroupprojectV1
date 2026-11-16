<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDialog,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElInput,
  ElMessage,
  ElMessageBox,
  ElOption,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus'
import { ApiError } from '../../types/api'
import {
  listTeacherTemplates,
  publishTrainingTemplate,
  unpublishTrainingTemplate,
} from './api'
import { formatDateTime, levelLabel } from './format'
import type { TrainingLevel, TrainingTemplateView } from './types'

/** Teacher template management (08 §12.8); source task detail owns the entry point. */

const templates = ref<TrainingTemplateView[]>([])
const loading = ref(true)
const errorMessage = ref('')
const levelFilter = ref<TrainingLevel | ''>('')

const dialogVisible = ref(false)
const publishing = ref(false)
const publishError = ref('')
const publishForm = ref({
  sourceTaskId: '',
  courseId: '',
  level: 'progressive' as TrainingLevel,
})

function weightsText(template: TrainingTemplateView): string {
  return Object.entries(template.weights)
    .map(([dimension, weight]) => `${dimension} ${weight}`)
    .join(' / ')
}

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const page = await listTeacherTemplates(levelFilter.value || null, null, 1, 50)
    templates.value = page.items
  } catch (error) {
    errorMessage.value = error instanceof ApiError
      ? error.message
      : '模板列表加载失败，请重试。'
  } finally {
    loading.value = false
  }
}

function openPublish(): void {
  publishError.value = ''
  publishForm.value = { sourceTaskId: '', courseId: '', level: 'progressive' }
  dialogVisible.value = true
}

async function publish(): Promise<void> {
  if (!publishForm.value.sourceTaskId.trim() || !publishForm.value.courseId.trim()) {
    publishError.value = '源任务与目标课程必填。'
    return
  }
  publishing.value = true
  publishError.value = ''
  try {
    await publishTrainingTemplate({
      source_task_id: publishForm.value.sourceTaskId.trim(),
      course_id: publishForm.value.courseId.trim(),
      training_level: publishForm.value.level,
    })
    ElMessage.success('训练模板已发布')
    dialogVisible.value = false
    await load()
  } catch (error) {
    publishError.value = error instanceof ApiError
      ? error.message
      : '发布失败，请重试。'
  } finally {
    publishing.value = false
  }
}

async function unpublish(template: TrainingTemplateView): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '下架后学生不再看到该模板；已选中训练记录保留至到期。',
      '下架模板',
      { confirmButtonText: '下架', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await unpublishTrainingTemplate(template.template_id)
    ElMessage.success('模板已下架')
    await load()
  } catch (error) {
    errorMessage.value = error instanceof ApiError
      ? error.message
      : '下架失败，请重试。'
  }
}

onMounted(load)
</script>

<template>
  <div class="teacher-page">
    <header class="teacher-page__header">
      <div>
        <h1>训练模板</h1>
        <p>从已发布 / 已关闭的自由试讲任务生成模板；目标课程可与源课程不同。</p>
      </div>
      <div class="teacher-page__actions">
        <ElSelect v-model="levelFilter" placeholder="全部档位" clearable @change="load">
          <ElOption label="进阶" value="progressive" />
          <ElOption label="模拟考核" value="mock_exam" />
        </ElSelect>
        <ElButton type="primary" @click="openPublish">发布模板</ElButton>
      </div>
    </header>

    <ElAlert v-if="errorMessage" type="error" :closable="false" show-icon>
      <template #title>{{ errorMessage }}</template>
    </ElAlert>

    <ElAlert type="info" :closable="false" show-icon>
      目标课程归档或你被移出目标课程老师组时，模板会自动下架；已选中训练记录保留至到期。
    </ElAlert>

    <p v-if="loading" class="state">加载中…</p>
    <ElEmpty v-else-if="templates.length === 0" description="还没有训练模板" />

    <ElCard v-else shadow="never">
      <ElTable :data="templates" stripe>
        <ElTableColumn label="课题 / 来源任务" min-width="220">
          <template #default="{ row }">
            <strong>{{ row.topic }}</strong>
            <p class="cell-sub">源任务 {{ row.source_task_id }}</p>
          </template>
        </ElTableColumn>
        <ElTableColumn label="目标课程" min-width="180">
          <template #default="{ row }">
            <span class="cell-sub">{{ row.course_id }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="档位" width="110">
          <template #default="{ row }">
            <ElTag type="warning" effect="plain">{{ levelLabel(row.training_level) }}</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="配置" min-width="220">
          <template #default="{ row }">
            <p class="cell-sub">
              {{ row.duration_seconds }}s / 最长 {{ row.max_duration_seconds }}s ·
              {{ row.virtual_student_count }} 名学生
            </p>
            <p class="cell-sub">{{ weightsText(row as TrainingTemplateView) }}</p>
            <p class="cell-sub">
              参考材料：{{ row.has_reference_material ? '有（仅评测使用）' : '无' }}
            </p>
          </template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="100">
          <template #default="{ row }">
            <ElTag :type="row.status === 'published' ? 'success' : 'info'">
              {{ row.status === 'published' ? '已发布' : '已下架' }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="发布时间" width="180">
          <template #default="{ row }">
            <span class="cell-sub">{{ formatDateTime(row.published_at) }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <ElButton
              v-if="row.status === 'published'"
              size="small"
              type="danger"
              text
              @click="unpublish(row as TrainingTemplateView)"
            >
              下架
            </ElButton>
            <span v-else class="cell-sub">—</span>
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>

    <ElDialog v-model="dialogVisible" title="发布为训练模板" width="520px">
      <ElAlert v-if="publishError" type="error" :closable="false" show-icon>
        <template #title>{{ publishError }}</template>
      </ElAlert>
      <ElForm label-width="100px">
        <ElFormItem label="源任务 ID">
          <ElInput v-model="publishForm.sourceTaskId" placeholder="已发布 / 已关闭的自由试讲任务 ID" />
        </ElFormItem>
        <ElFormItem label="目标课程 ID">
          <ElInput v-model="publishForm.courseId" placeholder="你所在的目标课程 ID" />
        </ElFormItem>
        <ElFormItem label="档位">
          <ElSelect v-model="publishForm.level" class="dialog-select">
            <ElOption label="进阶" value="progressive" />
            <ElOption label="模拟考核" value="mock_exam" />
          </ElSelect>
        </ElFormItem>
      </ElForm>
      <p class="dialog-hint">
        发布时复制源任务当前配置快照；之后源任务变化不影响模板。模拟考核固定为最高时长。
      </p>
      <template #footer>
        <ElButton @click="dialogVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="publishing" @click="publish">发布</ElButton>
      </template>
    </ElDialog>
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
}

.teacher-page__header p {
  margin: 0;
  color: #8fa3c0;
}

.teacher-page__actions {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.state {
  color: #8fa3c0;
}

.cell-sub {
  margin: 2px 0 0;
  color: #8fa3c0;
  font-size: 12px;
  word-break: break-all;
}

.dialog-select {
  width: 100%;
}

.dialog-hint {
  margin: 0;
  color: #8fa3c0;
  font-size: 12px;
}
</style>
