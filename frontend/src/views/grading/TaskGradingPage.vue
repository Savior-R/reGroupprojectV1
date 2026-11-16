<script setup lang="ts">
import { computed, onUnmounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDialog,
  ElEmpty,
  ElInput,
  ElInputNumber,
  ElMessage,
  ElMessageBox,
  ElOption,
  ElPagination,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus'
import M7PageHeader from './M7PageHeader.vue'
import {
  batchSaveTeacherScores,
  clearTeacherScores,
  downloadTaskCsv,
  getGradingConfig,
  listGradingSubmissions,
  saveTeacherScore,
  updateGradingConfig,
} from './api'
import type {
  BatchScoreItem,
  GradingConfigView,
  GradingResponse,
  GradingRow,
  SaveTeacherScoreBody,
  ScoringMode,
} from './api'
import {
  classOptions,
  dimensionLabel,
  effectiveStatus,
  formatScore,
  formatTime,
  modeLabel,
  round1,
  statusLabel,
  statusTagType,
  STUDENT_STATUS_LABELS,
} from './ui'
import { ApiError } from '../../types/api'

/**
 * Task grading page (07 §13.2—§13.5).
 *
 * Roster + summary + grade drawer + configuration drawer + batch scoring.
 * The page polls every 4 s while any submission is pending AI; the final score
 * appears automatically after M4 reaches a terminal state.
 */

const route = useRoute()
const courseId = computed(() => String(route.params.courseId ?? ''))
const taskId = computed(() => String(route.params.taskId ?? ''))

const config = ref<GradingConfigView | null>(null)
const data = ref<GradingResponse | null>(null)
const loading = ref(true)
const errorMessage = ref('')
const errorCode = ref('')

const classId = ref('')
const statusFilter = ref('')
const keyword = ref('')
const page = ref(1)
const size = ref(20)
const classChoices = ref<Array<{ value: string; label: string }>>([])

const statusChoices = [
  { value: 'completed', label: '已完成' },
  { value: 'pending_ai', label: '待 AI' },
  { value: 'pending_teacher', label: '待教师' },
  { value: 'ai_partial', label: 'AI 不完整' },
  { value: 'ai_failed', label: 'AI 失败' },
  { value: 'not_submitted', label: '未提交' },
  { value: 'in_progress', label: '进行中' },
]

const rows = computed(() => data.value?.items ?? [])
const summary = computed(() => data.value?.summary)
const aiPending = computed(() =>
  rows.value.some((row) => effectiveStatus(row) === 'pending_ai'),
)

let pollingTimer: ReturnType<typeof setInterval> | null = null

async function load(showLoading = true): Promise<void> {
  if (showLoading) {
    loading.value = true
  }
  errorMessage.value = ''
  errorCode.value = ''
  try {
    const [nextConfig, nextPage] = await Promise.all([
      getGradingConfig(taskId.value),
      listGradingSubmissions(taskId.value, {
        class_id: classId.value || undefined,
        status: statusFilter.value || undefined,
        keyword: keyword.value || undefined,
        page: page.value,
        size: size.value,
      }),
    ])
    config.value = nextConfig
    data.value = nextPage
    mergeClassChoices(nextPage.items)
    syncPolling()
  } catch (error) {
    stopPolling()
    if (error instanceof ApiError) {
      errorMessage.value = error.message
      errorCode.value = error.code
    } else {
      errorMessage.value = '评分名单加载失败，请重试。'
    }
  } finally {
    loading.value = false
  }
}

function mergeClassChoices(items: GradingRow[]): void {
  const known = new Map(classChoices.value.map((item) => [item.value, item.label]))
  for (const option of classOptions(items)) {
    known.set(option.value, option.label)
  }
  classChoices.value = [...known.entries()].map(([value, label]) => ({ value, label }))
}

function syncPolling(): void {
  if (aiPending.value && pollingTimer === null) {
    pollingTimer = setInterval(() => {
      if (document.visibilityState === 'visible') {
        void load(false)
      }
    }, 4000)
  } else if (!aiPending.value) {
    stopPolling()
  }
}

function stopPolling(): void {
  if (pollingTimer !== null) {
    clearInterval(pollingTimer)
    pollingTimer = null
  }
}

onUnmounted(stopPolling)

function applyFilters(): void {
  page.value = 1
  void load()
}

function resetFilters(): void {
  classId.value = ''
  statusFilter.value = ''
  keyword.value = ''
  page.value = 1
  void load()
}

function changePage(next: number): void {
  page.value = next
  void load()
}

/* ------------------------------------------------------------------ */
/* Grade drawer                                                        */
/* ------------------------------------------------------------------ */

const gradeDrawer = ref(false)
const selected = ref<GradingRow | null>(null)
const dimensionInputs = reactive<Record<string, number | undefined>>({})
const teacherTotalInput = ref<number | undefined>(undefined)
const commentInput = ref('')
const saving = ref(false)
const gradeError = ref('')

function rowStatus(row: unknown): string {
  return effectiveStatus(row as GradingRow)
}

function openGrade(requestRow: unknown): void {
  const row = requestRow as GradingRow
  if (!row.submission_id) {
    return
  }
  selected.value = row
  gradeError.value = ''
  commentInput.value = row.teacher_score?.comment ?? ''
  teacherTotalInput.value = row.teacher_score?.teacher_total
  for (const key of Object.keys(dimensionInputs)) {
    delete dimensionInputs[key]
  }
  if (config.value) {
    for (const dimension of config.value.dimensions) {
      dimensionInputs[dimension] =
        row.teacher_score?.dimension_scores?.[dimension] ?? undefined
    }
  }
  gradeDrawer.value = true
}

const teacherTotalPreview = computed<number | null>(() => {
  if (!config.value) {
    return null
  }
  if (config.value.scoring_mode === 'total') {
    return teacherTotalInput.value ?? null
  }
  let total = 0
  let complete = true
  for (const dimension of config.value.dimensions) {
    const value = dimensionInputs[dimension]
    const weight = config.value.rubric_weights[dimension] ?? 0
    if (value === null || value === undefined) {
      if (weight > 0) {
        complete = false
      }
      continue
    }
    total += value * (weight / 100)
  }
  return complete ? round1(total) : null
})

const finalPreview = computed<string>(() => {
  if (!config.value || !selected.value) {
    return '—'
  }
  const ai = selected.value.ai
  const teacherTotal = teacherTotalPreview.value
  if (config.value.ai_weight > 0) {
    if (!ai) {
      return '待 AI'
    }
    if (ai.status === 'partial') {
      return 'AI 不完整'
    }
    if (ai.status === 'failed') {
      return 'AI 失败'
    }
    if (ai.status !== 'completed' || ai.total_score === null) {
      return '待 AI'
    }
  }
  if (config.value.teacher_weight > 0 && teacherTotal === null) {
    return '待教师'
  }
  if (config.value.ai_weight === 0) {
    return formatScore(teacherTotal)
  }
  if (config.value.teacher_weight === 0) {
    return formatScore(ai?.total_score)
  }
  return formatScore(
    round1(
      ((ai?.total_score ?? 0) * config.value.ai_weight +
        (teacherTotal ?? 0) * config.value.teacher_weight) /
        100,
    ),
  )
})

async function saveGrade(): Promise<void> {
  if (!config.value || !selected.value?.submission_id) {
    return
  }
  saving.value = true
  gradeError.value = ''
  try {
    let body: SaveTeacherScoreBody
    if (config.value.scoring_mode === 'dimension') {
      const dimensions: Record<string, number> = {}
      for (const [dimension, value] of Object.entries(dimensionInputs)) {
        if (typeof value === 'number') {
          dimensions[dimension] = value
        }
      }
      body = {
        scoring_mode: config.value.scoring_mode,
        dimension_scores: dimensions,
        comment: commentInput.value,
        version: selected.value.teacher_score?.version ?? 0,
      }
    } else {
      body = {
        scoring_mode: config.value.scoring_mode,
        teacher_total: teacherTotalInput.value,
        comment: commentInput.value,
        version: selected.value.teacher_score?.version ?? 0,
      }
    }
    const result = await saveTeacherScore(selected.value.submission_id, body)
    ElMessage.success(
      result.final?.status === 'completed' ? '评分已保存，最终分已计算' : '评分已保存',
    )
    gradeDrawer.value = false
    await load(false)
  } catch (error) {
    if (error instanceof ApiError) {
      gradeError.value =
        error.code === 'TEACHER_SCORE_CONFLICT'
          ? '已被其他教师修改，请刷新后重试。'
          : error.message
    } else {
      gradeError.value = '保存失败，请重试。'
    }
  } finally {
    saving.value = false
  }
}

/* ------------------------------------------------------------------ */
/* Config drawer                                                       */
/* ------------------------------------------------------------------ */

const configDrawer = ref(false)
const configAiWeight = ref(70)
const configTeacherWeight = ref(30)
const configMode = ref<ScoringMode>('dimension')
const configSaving = ref(false)
const configError = ref('')
const clearing = ref(false)

const configTotal = computed(() => configAiWeight.value + configTeacherWeight.value)
const configValid = computed(
  () =>
    configTotal.value === 100 &&
    configAiWeight.value >= 0 &&
    configAiWeight.value <= 100 &&
    configTeacherWeight.value >= 0 &&
    configTeacherWeight.value <= 100 &&
    configSaving.value === false,
)

function openConfig(): void {
  if (!config.value) {
    return
  }
  configAiWeight.value = config.value.ai_weight
  configTeacherWeight.value = config.value.teacher_weight
  configMode.value = config.value.scoring_mode
  configError.value = ''
  configDrawer.value = true
}

function syncWeights(source: 'ai' | 'teacher'): void {
  if (source === 'ai') {
    configTeacherWeight.value = Math.max(0, 100 - configAiWeight.value)
  } else {
    configAiWeight.value = Math.max(0, 100 - configTeacherWeight.value)
  }
}

async function saveConfig(): Promise<void> {
  if (!config.value || !configValid.value) {
    return
  }
  configSaving.value = true
  configError.value = ''
  try {
    config.value = await updateGradingConfig(taskId.value, {
      ai_weight: configAiWeight.value,
      teacher_weight: configTeacherWeight.value,
      scoring_mode: configMode.value,
      config_version: config.value.config_version,
    })
    ElMessage.success('评分配置已保存，最终分已重算')
    configDrawer.value = false
    await load(false)
  } catch (error) {
    configError.value =
      error instanceof ApiError ? error.message : '保存失败，请重试。'
  } finally {
    configSaving.value = false
  }
}

async function clearScores(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '将删除该任务全部教师评分并解除权重 / 模式锁定，最终分重算为待评分。此操作不可撤销。',
      '清空全部评分',
      { type: 'warning', confirmButtonText: '确认清空', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  clearing.value = true
  configError.value = ''
  try {
    const result = await clearTeacherScores(taskId.value)
    ElMessage.success(
      `已清空 ${result.deleted_count} 条评分，重算 ${result.recomputed} 条最终分`,
    )
    configDrawer.value = false
    await load(false)
  } catch (error) {
    configError.value =
      error instanceof ApiError ? error.message : '清空失败，请重试。'
  } finally {
    clearing.value = false
  }
}

/* ------------------------------------------------------------------ */
/* Batch drawer                                                        */
/* ------------------------------------------------------------------ */

interface BatchEdit {
  dimensions: Record<string, number | undefined>
  total: number | undefined
  comment: string
  version: number
  originalDimensions: Record<string, number | undefined>
  originalTotal: number | undefined
  originalComment: string
}

type BatchRow = GradingRow & { submission_id: string }

const batchDrawer = ref(false)
const batchEdits = reactive<Record<string, BatchEdit>>({})
const batchSaving = ref(false)
const batchLoading = ref(false)
const batchError = ref('')
const batchData = ref<GradingRow[]>([])
const batchResults = ref<Array<{ student: string; ok: boolean; message: string }>>([])

const batchRows = computed<BatchRow[]>(() =>
  batchData.value.filter((row): row is BatchRow => row.submission_id !== null),
)

function setBatchDimension(
  submissionId: string,
  dimension: string,
  value: number | undefined,
): void {
  const edit = batchEdits[submissionId]
  if (edit) {
    edit.dimensions[dimension] = value
  }
}

function setBatchTotal(submissionId: string, value: number | undefined): void {
  const edit = batchEdits[submissionId]
  if (edit) {
    edit.total = value
  }
}

function setBatchComment(submissionId: string, value: string): void {
  const edit = batchEdits[submissionId]
  if (edit) {
    edit.comment = value
  }
}

async function openBatch(): Promise<void> {
  if (!config.value) {
    return
  }
  batchDrawer.value = true
  batchLoading.value = true
  batchError.value = ''
  batchResults.value = []
  for (const key of Object.keys(batchEdits)) {
    delete batchEdits[key]
  }
  try {
    const first = await listGradingSubmissions(taskId.value, {
      class_id: classId.value || undefined,
      status: statusFilter.value || undefined,
      keyword: keyword.value || undefined,
      page: 1,
      size: 100,
    })
    const all = [...first.items]
    if (first.total > all.length) {
      const second = await listGradingSubmissions(taskId.value, {
        class_id: classId.value || undefined,
        status: statusFilter.value || undefined,
        keyword: keyword.value || undefined,
        page: 2,
        size: 100,
      })
      all.push(...second.items)
    }
    batchData.value = all
    for (const row of batchData.value) {
      if (!row.submission_id) {
        continue
      }
      const dimensions: Record<string, number | undefined> = {}
      for (const dimension of config.value.dimensions) {
        dimensions[dimension] =
          row.teacher_score?.dimension_scores?.[dimension] ?? undefined
      }
      const total = row.teacher_score?.teacher_total
      const comment = row.teacher_score?.comment ?? ''
      batchEdits[row.submission_id] = {
        dimensions,
        total,
        comment,
        version: row.teacher_score?.version ?? 0,
        originalDimensions: { ...dimensions },
        originalTotal: total,
        originalComment: comment,
      }
    }
  } catch (error) {
    batchError.value =
      error instanceof ApiError ? error.message : '批量名单加载失败，请重试。'
    batchData.value = []
  } finally {
    batchLoading.value = false
  }
}

function batchTotal(submissionId: string): number | null {
  if (!config.value) {
    return null
  }
  const edit = batchEdits[submissionId]
  if (!edit) {
    return null
  }
  if (config.value.scoring_mode === 'total') {
    return edit.total ?? null
  }
  let total = 0
  let complete = true
  for (const dimension of config.value.dimensions) {
    const value = edit.dimensions[dimension]
    const weight = config.value.rubric_weights[dimension] ?? 0
    if (value === null || value === undefined) {
      if (weight > 0) {
        complete = false
      }
      continue
    }
    total += value * (weight / 100)
  }
  return complete ? round1(total) : null
}

async function saveBatch(): Promise<void> {
  if (!config.value) {
    return
  }
  const items: BatchScoreItem[] = []
  for (const row of batchRows.value) {
    const submissionId = row.submission_id
    const edit = batchEdits[submissionId]
    if (!edit) {
      continue
    }
    const dimensionsChanged = Object.keys(edit.dimensions).some(
      (dimension) =>
        edit.dimensions[dimension] !== edit.originalDimensions[dimension],
    )
    const changed =
      dimensionsChanged ||
      edit.total !== edit.originalTotal ||
      edit.comment !== edit.originalComment
    if (!changed) {
      continue
    }
    if (config.value.scoring_mode === 'dimension') {
      const dimensions: Record<string, number> = {}
      for (const [dimension, value] of Object.entries(edit.dimensions)) {
        if (typeof value === 'number') {
          dimensions[dimension] = value
        }
      }
      if (Object.keys(dimensions).length === 0) {
        continue
      }
      items.push({
        submission_id: submissionId,
        scoring_mode: 'dimension',
        dimension_scores: dimensions,
        comment: edit.comment,
        version: edit.version,
      })
    } else {
      if (edit.total === null || edit.total === undefined) {
        continue
      }
      items.push({
        submission_id: submissionId,
        scoring_mode: 'total',
        teacher_total: edit.total,
        comment: edit.comment,
        version: edit.version,
      })
    }
  }
  if (items.length === 0) {
    batchError.value = '没有需要保存的评分。'
    return
  }
  batchSaving.value = true
  batchError.value = ''
  try {
    const names = new Map(
      batchRows.value.map((row) => [row.submission_id, row.student_name]),
    )
    const results: Array<{ student: string; ok: boolean; message: string }> = []
    let success = 0
    let failure = 0
    // The contract caps one request at 100 rows; split larger batches serially.
    for (let index = 0; index < items.length; index += 100) {
      const response = await batchSaveTeacherScores(
        taskId.value,
        items.slice(index, index + 100),
      )
      success += response.success_count
      failure += response.failure_count
      for (const result of response.results) {
        results.push({
          student: names.get(result.submission_id) ?? result.submission_id,
          ok: result.ok,
          message: result.ok
            ? '已保存'
            : `${result.error_code ?? '保存失败'} ${result.message ?? ''}`.trim(),
        })
      }
    }
    batchResults.value = results
    ElMessage.success(`成功 ${success} 行，失败 ${failure} 行`)
    await load(false)
  } catch (error) {
    batchError.value =
      error instanceof ApiError ? error.message : '批量保存失败，请重试。'
  } finally {
    batchSaving.value = false
  }
}

/* ------------------------------------------------------------------ */
/* Export                                                              */
/* ------------------------------------------------------------------ */

const exporting = ref(false)

async function exportCsv(): Promise<void> {
  exporting.value = true
  try {
    const download = await downloadTaskCsv(taskId.value, {
      classId: classId.value || undefined,
      status: statusFilter.value || undefined,
      keyword: keyword.value || undefined,
    })
    const url = URL.createObjectURL(download.blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = download.filename
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    URL.revokeObjectURL(url)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '导出失败，请重试。')
  } finally {
    exporting.value = false
  }
}

void load()
</script>

<template>
  <div class="m7-page">
    <M7PageHeader
      title="任务评分"
      :subtitle="config ? `${modeLabel(config.mode)} · 权重 ${config.ai_weight} / ${config.teacher_weight} · ${config.scoring_mode === 'dimension' ? '维度模式' : '总分模式'}` : undefined"
      :course-id="courseId"
      :task-id="taskId"
    />

    <ElAlert
      v-if="errorMessage"
      type="error"
      :closable="false"
      show-icon
      class="m7-alert"
    >
      <template #title>{{ errorMessage }}</template>
      <p v-if="errorCode" class="m7-error-code">错误码：{{ errorCode }}</p>
      <p v-if="errorCode === 'FORBIDDEN' || errorCode === 'TEACHER_NOT_IN_GROUP'" class="m7-error-code">
        请切换到教师视角后重试。
      </p>
      <ElButton size="small" @click="load()">重试</ElButton>
    </ElAlert>

    <p v-else-if="loading" class="m7-state">加载中…</p>

    <template v-else-if="config">
      <ElCard shadow="never" class="m7-card">
        <div class="m7-toolbar">
          <div class="m7-toolbar__meta">
            <ElTag type="info">{{ modeLabel(config.mode) }}</ElTag>
            <span>AI / 教师权重：{{ config.ai_weight }} / {{ config.teacher_weight }}</span>
            <span>打分模式：{{ config.scoring_mode === 'dimension' ? '维度分' : '总分' }}</span>
            <ElTag :type="config.locked_at ? 'warning' : 'success'" size="small">
              {{ config.locked_at ? '已锁定' : '未锁定' }}
            </ElTag>
          </div>
          <div class="m7-toolbar__actions">
            <ElButton @click="openConfig">评分配置</ElButton>
            <ElButton :loading="exporting" @click="exportCsv">导出 CSV</ElButton>
            <ElButton type="primary" @click="openBatch">批量评分</ElButton>
          </div>
        </div>

        <div v-if="summary" class="m7-summary">
          <div class="m7-summary__item"><span>分配</span><strong>{{ summary.assigned }}</strong></div>
          <div class="m7-summary__item"><span>提交</span><strong>{{ summary.submitted }}</strong></div>
          <div class="m7-summary__item"><span>已完成</span><strong>{{ summary.completed }}</strong></div>
          <div class="m7-summary__item"><span>待 AI</span><strong>{{ summary.pending_ai }}</strong></div>
          <div class="m7-summary__item"><span>待教师</span><strong>{{ summary.pending_teacher }}</strong></div>
          <div class="m7-summary__item"><span>进行中</span><strong>{{ summary.in_progress }}</strong></div>
        </div>

        <div class="m7-filters">
          <ElSelect v-model="classId" placeholder="全部班级" clearable style="width: 180px" @change="applyFilters">
            <ElOption v-for="item in classChoices" :key="item.value" :label="item.label" :value="item.value" />
          </ElSelect>
          <ElSelect v-model="statusFilter" placeholder="全部状态" clearable style="width: 160px" @change="applyFilters">
            <ElOption v-for="item in statusChoices" :key="item.value" :label="item.label" :value="item.value" />
          </ElSelect>
          <ElInput v-model="keyword" placeholder="学生姓名 / 账号 ID" clearable style="width: 220px" @keyup.enter="applyFilters" />
          <ElButton @click="applyFilters">筛选</ElButton>
          <ElButton @click="resetFilters">重置</ElButton>
        </div>

        <ElTable :data="rows" row-key="student_account_id" stripe class="m7-table">
          <ElTableColumn label="学生" min-width="130">
            <template #default="{ row }">
              <span>{{ row.student_name }}</span>
              <ElTag
                v-if="row.student_status !== 'active'"
                size="small"
                type="info"
                class="m7-inline-tag"
              >
                {{ STUDENT_STATUS_LABELS[row.student_status] ?? row.student_status }}
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn prop="class_name" label="班级" min-width="120" />
          <ElTableColumn label="提交时间" width="170">
            <template #default="{ row }">{{ formatTime(row.submitted_at) }}</template>
          </ElTableColumn>
          <ElTableColumn label="AI" width="90" align="right">
            <template #default="{ row }">
              <span :class="{ 'm7-score--muted': row.ai?.total_score == null }">
                {{ formatScore(row.ai?.total_score) }}
              </span>
            </template>
          </ElTableColumn>
          <ElTableColumn label="教师" width="90" align="right">
            <template #default="{ row }">{{ formatScore(row.teacher_score?.teacher_total) }}</template>
          </ElTableColumn>
          <ElTableColumn label="最终" width="100" align="right">
            <template #default="{ row }">
              <strong>{{ formatScore(row.final?.final_total) }}</strong>
            </template>
          </ElTableColumn>
          <ElTableColumn label="状态" width="130">
            <template #default="{ row }">
              <ElTag :type="statusTagType(rowStatus(row))" size="small">
                {{ statusLabel(rowStatus(row)) }}
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <ElButton
                size="small"
                :disabled="!row.submission_id"
                @click="openGrade(row)"
              >
                评分
              </ElButton>
            </template>
          </ElTableColumn>
        </ElTable>

        <ElEmpty v-if="rows.length === 0" description="暂无分配学生" />

        <ElPagination
          v-if="data && data.total > 0"
          class="m7-pagination"
          layout="prev, pager, next, total"
          :current-page="page"
          :page-size="size"
          :total="data.total"
          @current-change="changePage"
        />
      </ElCard>
    </template>

    <ElDialog v-model="gradeDrawer" title="教师评分" width="560px">
      <template v-if="selected">
        <div class="m7-dialog-head">
          <strong>{{ selected.student_name }}</strong>
          <span>{{ selected.class_name }}</span>
        </div>
        <div class="m7-ai-line">
          AI：{{ formatScore(selected.ai?.total_score) }}
          <ElTag size="small" type="info">{{ selected.ai?.status ?? '未创建' }}</ElTag>
        </div>

        <template v-if="config?.scoring_mode === 'dimension'">
          <div v-for="dimension in config.dimensions" :key="dimension" class="m7-score-row">
            <span>{{ dimensionLabel(dimension) }}（权重 {{ config.rubric_weights[dimension] }}）</span>
            <ElInputNumber
              v-model="dimensionInputs[dimension]"
              :min="0"
              :max="100"
              :step="0.1"
              :precision="1"
              controls-position="right"
            />
          </div>
        </template>
        <div v-else class="m7-score-row">
          <span>教师总分</span>
          <ElInputNumber
            v-model="teacherTotalInput"
            :min="0"
            :max="100"
            :step="0.1"
            :precision="1"
            controls-position="right"
          />
        </div>

        <div class="m7-preview">
          <span>教师总分：<strong>{{ formatScore(teacherTotalPreview) }}</strong></span>
          <span>最终预览：<strong>{{ finalPreview }}</strong></span>
        </div>

        <ElInput
          v-model="commentInput"
          type="textarea"
          :rows="3"
          maxlength="1000"
          show-word-limit
          placeholder="教师评语（纯文本，最多 1000 字）"
        />

        <ElAlert v-if="gradeError" type="error" :closable="false" class="m7-alert" :title="gradeError" />
      </template>
      <template #footer>
        <ElButton @click="gradeDrawer = false">取消</ElButton>
        <ElButton type="primary" :loading="saving" @click="saveGrade">保存</ElButton>
      </template>
    </ElDialog>

    <ElDialog v-model="configDrawer" title="评分配置" width="520px">
      <template v-if="config">
        <div class="m7-config-row">
          <span>AI 分权重</span>
          <ElInputNumber v-model="configAiWeight" :min="0" :max="100" :step="5" :disabled="!config?.can_edit" @change="syncWeights('ai')" />
        </div>
        <div class="m7-config-row">
          <span>教师分权重</span>
          <ElInputNumber v-model="configTeacherWeight" :min="0" :max="100" :step="5" :disabled="!config?.can_edit" @change="syncWeights('teacher')" />
        </div>
        <p class="m7-config-total" :class="{ 'm7-config-total--bad': configTotal !== 100 }">
          合计：{{ configTotal }} / 100
        </p>
        <div class="m7-config-row">
          <span>打分模式</span>
          <el-radio-group v-model="configMode" :disabled="!config?.can_edit">
            <el-radio-button value="dimension">维度分</el-radio-button>
            <el-radio-button value="total">总分</el-radio-button>
          </el-radio-group>
        </div>
        <ElAlert
          v-if="config.locked_at || config.has_scores"
          type="warning"
          :closable="false"
          class="m7-alert"
          title="首次评分后权重与模式已锁定；如需修改，请先清空全部评分。"
        />
        <ElAlert
          v-else
          type="info"
          :closable="false"
          class="m7-alert"
          title="未锁定：修改后将实时重算该任务全部最终分。"
        />
        <ElAlert v-if="configError" type="error" :closable="false" class="m7-alert" :title="configError" />
        <ElButton type="danger" plain :loading="clearing" @click="clearScores">清空全部评分</ElButton>
      </template>
      <template #footer>
        <ElButton @click="configDrawer = false">取消</ElButton>
        <ElButton
          type="primary"
          :loading="configSaving"
          :disabled="!configValid || !config?.can_edit"
          @click="saveConfig"
        >
          保存
        </ElButton>
      </template>
    </ElDialog>

    <ElDialog v-model="batchDrawer" title="批量评分" width="92%" top="4vh">
      <template v-if="config">
        <p class="m7-batch-hint">
          仅提交有变更的行；成功行立即生效，失败行保留输入并返回原因。前端按 100 行 / 次分批提交。
        </p>
        <ElAlert v-if="batchError" type="error" :closable="false" class="m7-alert" :title="batchError" />
        <p v-if="batchLoading" class="m7-state">加载名单中…</p>
        <div v-else class="m7-batch-scroll">
          <ElTable :data="batchRows" row-key="student_account_id" size="small" border>
            <ElTableColumn prop="student_name" label="学生" width="120" fixed />
            <ElTableColumn prop="class_name" label="班级" width="120" fixed />
            <ElTableColumn v-for="dimension in config.dimensions" :key="dimension" :label="dimensionLabel(dimension)" width="130">
              <template #default="{ row }">
                <ElInputNumber
                  v-if="config.scoring_mode === 'dimension'"
                  :model-value="batchEdits[row.submission_id]?.dimensions[dimension]"
                  :min="0"
                  :max="100"
                  :step="0.1"
                  :precision="1"
                  size="small"
                  controls-position="right"
                  @update:model-value="(value) => setBatchDimension(row.submission_id, dimension, value)"
                />
                <span v-else>—</span>
              </template>
            </ElTableColumn>
            <ElTableColumn v-if="config.scoring_mode === 'total'" label="教师总分" width="140">
              <template #default="{ row }">
                <ElInputNumber
                  :model-value="batchEdits[row.submission_id]?.total"
                  :min="0"
                  :max="100"
                  :step="0.1"
                  :precision="1"
                  size="small"
                  controls-position="right"
                  @update:model-value="(value) => setBatchTotal(row.submission_id, value)"
                />
              </template>
            </ElTableColumn>
            <ElTableColumn label="教师总分（计算）" width="130" align="right">
              <template #default="{ row }">{{ formatScore(batchTotal(row.submission_id)) }}</template>
            </ElTableColumn>
            <ElTableColumn label="评语" min-width="220">
              <template #default="{ row }">
                <ElInput
                  :model-value="batchEdits[row.submission_id]?.comment ?? ''"
                  size="small"
                  maxlength="1000"
                  @update:model-value="(value) => setBatchComment(row.submission_id, value)"
                />
              </template>
            </ElTableColumn>
          </ElTable>
        </div>
        <div v-if="batchResults.length" class="m7-batch-results">
          <div v-for="item in batchResults" :key="item.student">
            <ElTag :type="item.ok ? 'success' : 'danger'" size="small">{{ item.student }}</ElTag>
            <span>{{ item.message }}</span>
          </div>
        </div>
      </template>
      <template #footer>
        <ElButton @click="batchDrawer = false">关闭</ElButton>
        <ElButton type="primary" :loading="batchSaving" @click="saveBatch">保存全部</ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<style scoped>
.m7-page {
  max-width: 1320px;
  margin: 0 auto;
  padding: 24px;
  color: #e8eef9;
}

.m7-card {
  border: 1px solid #1f2b44;
  background: #0f1a2e;
}

.m7-state {
  color: #8fa3c0;
}

.m7-alert {
  margin: 12px 0;
}

.m7-error-code {
  margin: 4px 0;
  color: #8fa3c0;
  font-size: 12px;
}

.m7-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.m7-toolbar__meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  color: #8fa3c0;
  font-size: 13px;
}

.m7-toolbar__actions {
  display: flex;
  gap: 8px;
}

.m7-summary {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(110px, 1fr));
  gap: 10px;
  margin: 16px 0;
}

.m7-summary__item {
  padding: 10px 12px;
  border: 1px solid #1f2b44;
  border-radius: 10px;
  background: #111c33;
}

.m7-summary__item span {
  display: block;
  color: #8fa3c0;
  font-size: 12px;
}

.m7-summary__item strong {
  font-size: 20px;
  color: #f5a524;
}

.m7-filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.m7-table {
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-header-bg-color: #111c33;
  --el-table-row-hover-bg-color: #17233c;
}

.m7-inline-tag {
  margin-left: 6px;
}

.m7-score--muted {
  color: #6f819e;
}

.m7-pagination {
  margin-top: 16px;
  justify-content: flex-end;
}

.m7-dialog-head {
  display: flex;
  justify-content: space-between;
  margin-bottom: 8px;
}

.m7-ai-line {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  color: #8fa3c0;
}

.m7-score-row,
.m7-config-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 10px;
}

.m7-preview {
  display: flex;
  justify-content: space-between;
  margin: 14px 0;
  padding: 10px 12px;
  border-radius: 8px;
  background: #111c33;
  color: #8fa3c0;
}

.m7-preview strong {
  color: #f5a524;
}

.m7-config-total {
  margin: 0 0 12px;
  color: #3dd68c;
  font-size: 13px;
}

.m7-config-total--bad {
  color: #ff6b6b;
}

.m7-batch-hint {
  margin: 0 0 12px;
  color: #8fa3c0;
  font-size: 13px;
}

.m7-batch-scroll {
  max-height: 58vh;
  overflow: auto;
}

.m7-batch-results {
  display: grid;
  gap: 6px;
  margin-top: 12px;
  font-size: 13px;
  color: #8fa3c0;
}
</style>
