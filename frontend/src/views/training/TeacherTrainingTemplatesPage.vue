<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ElAlert,
  ElButton,
  ElCard,
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
import { deleteMyTemplate, listMyTemplates } from './api'
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

const activeCount = computed(() => templates.value.filter((item) => item.status === 'active').length)

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
        <ElTableColumn label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <ElButton size="small" text @click="openDetail(row as TrainingTemplateSummary)">
              查看 / 编辑
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
</style>
