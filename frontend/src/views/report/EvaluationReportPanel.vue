<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElAlert, ElButton, ElCard, ElEmpty, ElProgress, ElTag } from 'element-plus'
import { ApiError } from '../../types/api'
import { getEvaluation, getReport } from './api'
import RadarChart from './RadarChart.vue'
import type { EvaluationStatus, EvaluationView, IssueView, ReportView } from './types'
import {
  DIMENSION_LABELS,
  MODE_LABELS,
  REASON_LABELS,
  SEVERITY_LABELS,
  SEVERITY_TYPES,
  STATUS_LABELS,
  STATUS_TYPES,
  dimensionLabel,
  formatDateTime,
  formatTimestamp,
  reasonLabel,
  subItemLabel,
} from './labels'

/**
 * Evaluation report body (06 §9.2, §10). Used by the evaluation / submission /
 * training-record entry pages. While the evaluation is pending or running the
 * panel polls the status endpoint every 3 s.
 */

const props = defineProps<{ evaluationId: string }>()
const emit = defineEmits<{ (event: 'resolved', evaluation: EvaluationView): void }>()

const POLL_INTERVAL_MS = 3_000
const NOT_READY = 'EVALUATION_NOT_READY'

const router = useRouter()
const loading = ref(true)
const status = ref<EvaluationStatus>('pending')
const evaluation = ref<EvaluationView | null>(null)
const report = ref<ReportView | null>(null)
const errorMessage = ref('')
const errorCode = ref('')
const forbidden = ref(false)
const notFound = ref(false)
const disposed = ref(false)

let pollTimer: ReturnType<typeof setTimeout> | null = null

const isWaiting = computed(() => status.value === 'pending' || status.value === 'running')
const isFailed = computed(() => status.value === 'failed')
const hasReport = computed(() => report.value !== null)

const totalDisplay = computed(() => {
  const total = report.value?.total_score
  return total === null || total === undefined ? '—' : total.toFixed(1)
})

const statusLabel = computed(() => STATUS_LABELS[status.value] ?? status.value)
const statusType = computed(() => STATUS_TYPES[status.value] ?? 'info')

const modeLabel = computed(() =>
  evaluation.value ? (MODE_LABELS[evaluation.value.mode] ?? evaluation.value.mode) : '',
)

const missingLabels = computed(() =>
  (report.value?.missing_dimensions ?? []).map((dimension) => dimensionLabel(dimension)),
)

const partialReasonLabels = computed(() =>
  (report.value?.partial_reasons ?? []).map((reason) => REASON_LABELS[reason] ?? reason),
)

const warningLabels = computed(() =>
  (report.value?.warnings ?? []).map((warning) => REASON_LABELS[warning] ?? warning),
)

function clearTimer(): void {
  if (pollTimer !== null) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

function schedulePoll(): void {
  clearTimer()
  if (disposed.value) {
    return
  }
  pollTimer = setTimeout(() => {
    void load(false)
  }, POLL_INTERVAL_MS)
}

function resetError(): void {
  errorMessage.value = ''
  errorCode.value = ''
  forbidden.value = false
  notFound.value = false
}

function handleError(error: unknown): void {
  if (error instanceof ApiError) {
    errorCode.value = error.code
    errorMessage.value = error.message
    forbidden.value = error.status === 403
    notFound.value = error.status === 404
    return
  }
  errorMessage.value = '评测报告加载失败，请稍后重试。'
}

async function load(initial: boolean): Promise<void> {
  if (initial) {
    loading.value = true
    resetError()
  }
  try {
    const current = await getEvaluation(props.evaluationId)
    evaluation.value = current
    status.value = current.status
    emit('resolved', current)

    if (current.status === 'pending' || current.status === 'running') {
      report.value = null
      loading.value = false
      schedulePoll()
      return
    }
    if (current.status === 'failed') {
      report.value = null
      loading.value = false
      return
    }

    const data = await getReport(props.evaluationId)
    report.value = data
    status.value = data.status
    loading.value = false
    resetError()
  } catch (error) {
    if (error instanceof ApiError && (error.code === NOT_READY || error.status === 409)) {
      // The status and report reads raced with the worker's terminal callback.
      loading.value = false
      schedulePoll()
      return
    }
    loading.value = false
    report.value = null
    handleError(error)
  }
}

function retry(): void {
  void load(true)
}

function jumpToReplay(issue: IssueView): void {
  const sessionId = report.value?.session_id
  if (!sessionId) {
    return
  }
  const target = issue.start_ms ?? 0
  void router.push({
    path: `/student/sessions/${encodeURIComponent(sessionId)}/review`,
    query: { t: String(target) },
  })
}

onMounted(() => {
  void load(true)
})

onBeforeUnmount(() => {
  disposed.value = true
  clearTimer()
})

watch(
  () => props.evaluationId,
  () => {
    clearTimer()
    report.value = null
    void load(true)
  },
)
</script>

<template>
  <section class="report-panel">
    <p v-if="loading" class="report-state">评测报告加载中…</p>

    <ElAlert
      v-else-if="errorMessage"
      type="error"
      :closable="false"
      show-icon
      class="report-state"
    >
      <template #title>
        {{ forbidden ? '无权查看该评测报告' : notFound ? '评测报告不存在' : errorMessage }}
      </template>
      <p v-if="errorCode" class="report-code">错误码：{{ errorCode }}</p>
      <ElButton v-if="!forbidden && !notFound" size="small" @click="retry">重试</ElButton>
    </ElAlert>

    <ElCard v-else-if="isWaiting" class="report-state" shadow="never">
      <ElTag :type="statusType">{{ statusLabel }}</ElTag>
      <p class="report-state__title">
        {{ status === 'pending' ? '评测任务已排队，正在等待 worker 处理' : 'AI 正在逐维度评测课堂' }}
      </p>
      <p class="report-state__hint">
        通常需要 1—3 分钟；页面每 3 秒自动刷新，完成后会展示总分、雷达图和改进建议。
      </p>
      <p v-if="modeLabel" class="report-state__meta">{{ modeLabel }} · 尝试 {{ evaluation?.attempt_count ?? 0 }} 次</p>
    </ElCard>

    <ElCard v-else-if="isFailed" class="report-state" shadow="never">
      <ElTag type="danger">评测失败</ElTag>
      <p class="report-state__title">本次评测未能生成结果。</p>
      <p v-if="evaluation?.error_code" class="report-state__meta">
        错误码：{{ evaluation.error_code }}<span v-if="evaluation.error_message"> · {{ evaluation.error_message }}</span>
      </p>
      <ul v-if="evaluation?.partial_reasons?.length" class="report-reasons">
        <li v-for="reason in evaluation.partial_reasons" :key="reason">
          {{ reasonLabel(reason) }}
        </li>
      </ul>
      <p class="report-state__hint">系统已记录失败原因；需要重跑时请联系教师或管理员。</p>
    </ElCard>

    <template v-else-if="hasReport && report">
      <ElCard class="report-hero" shadow="never">
        <div class="report-hero__main">
          <div>
            <p class="report-hero__eyebrow">
              <ElTag :type="statusType" size="small">{{ statusLabel }}</ElTag>
              <span>{{ modeLabel }}</span>
            </p>
            <p class="report-hero__score" :class="{ 'report-hero__score--missing': report.total_score === null }">
              {{ totalDisplay }}
              <span class="report-hero__unit">/ 100</span>
            </p>
            <p class="report-hero__meta">
              完成时间：{{ formatDateTime(report.meta.completed_at) }} ·
              尝试 {{ report.meta.attempt_count }} 次
            </p>
          </div>
          <p v-if="report.total_score === null" class="report-hero__note">
            存在缺失或失败的必需维度，按规则不计算总分。
          </p>
          <p v-else class="report-hero__note">总分按快照权重加权，保留一位小数。</p>
        </div>
      </ElCard>

      <ElAlert
        v-if="status === 'partial'"
        class="report-alert"
        type="warning"
        :closable="false"
        show-icon
        title="本次报告为部分完成"
      >
        <p v-if="missingLabels.length">缺失维度：{{ missingLabels.join('、') }}</p>
        <ul v-if="partialReasonLabels.length" class="report-reasons">
          <li v-for="reason in partialReasonLabels" :key="reason">{{ reason }}</li>
        </ul>
      </ElAlert>

      <div class="report-grid">
        <ElCard class="report-block" shadow="never">
          <template #header>
            <h2 class="report-block__title">维度雷达</h2>
          </template>
          <RadarChart
            :axes="report.radar.axes.map(dimensionLabel)"
            :values="report.radar.values"
          />
        </ElCard>

        <ElCard class="report-block" shadow="never">
          <template #header>
            <h2 class="report-block__title">维度得分</h2>
          </template>
          <div class="dimension-list">
            <article
              v-for="dimension in report.dimensions"
              :key="dimension.dimension"
              class="dimension-card"
            >
              <header class="dimension-card__header">
                <h3>{{ DIMENSION_LABELS[dimension.dimension] ?? dimension.dimension }}</h3>
                <span class="dimension-card__score">{{ dimension.score.toFixed(1) }}</span>
                <span class="dimension-card__weight">
                  权重 {{ report.weights[dimension.dimension] ?? '—' }}%
                </span>
              </header>
              <dl class="dimension-card__subs">
                <div
                  v-for="(score, key) in dimension.sub_scores"
                  :key="key"
                  class="dimension-card__sub"
                >
                  <dt>{{ subItemLabel(String(key)) }}</dt>
                  <dd>
                    <ElProgress
                      :percentage="Math.max(0, Math.min(100, score))"
                      :stroke-width="6"
                      :show-text="false"
                    />
                    <span>{{ score.toFixed(1) }}</span>
                  </dd>
                </div>
              </dl>
            </article>
            <ElEmpty
              v-if="report.dimensions.length === 0"
              description="该报告没有可展示的维度得分"
            />
          </div>
        </ElCard>
      </div>

      <ElCard class="report-block" shadow="never">
        <template #header>
          <h2 class="report-block__title">改进建议</h2>
        </template>
        <ol v-if="report.suggestions.length" class="suggestion-list">
          <li
            v-for="issue in report.suggestions"
            :key="issue.issue_id"
            class="suggestion"
          >
            <div class="suggestion__header">
              <span class="suggestion__rank">{{ issue.suggestion_rank ?? '·' }}</span>
              <ElTag size="small" type="info">
                {{ DIMENSION_LABELS[issue.dimension] ?? issue.dimension }}
              </ElTag>
              <ElTag size="small" :type="SEVERITY_TYPES[issue.severity]">
                {{ SEVERITY_LABELS[issue.severity] }}
              </ElTag>
              <span class="suggestion__time">
                {{ formatTimestamp(issue.start_ms) }}
                <template v-if="issue.end_ms !== null">— {{ formatTimestamp(issue.end_ms) }}</template>
              </span>
              <ElButton
                v-if="report.session_id"
                link
                type="primary"
                size="small"
                @click="jumpToReplay(issue)"
              >
                回看该片段
              </ElButton>
            </div>
            <p class="suggestion__evidence">{{ issue.evidence }}</p>
            <p class="suggestion__action">{{ issue.suggestion }}</p>
          </li>
        </ol>
        <ElEmpty v-else description="暂无改进建议" />
      </ElCard>

      <ElCard v-if="warningLabels.length" class="report-block" shadow="never">
        <template #header>
          <h2 class="report-block__title">评测提示</h2>
        </template>
        <ul class="report-reasons">
          <li v-for="warning in warningLabels" :key="warning">{{ warning }}</li>
        </ul>
      </ElCard>

      <ElCard class="report-block report-meta" shadow="never">
        <template #header>
          <h2 class="report-block__title">评测信息</h2>
        </template>
        <dl class="report-meta__grid">
          <div><dt>模型版本</dt><dd>{{ report.meta.model_version ?? '—' }}</dd></div>
          <div><dt>Prompt 版本</dt><dd>{{ report.meta.prompt_version ?? '—' }}</dd></div>
          <div><dt>评分规则</dt><dd>{{ report.meta.rubric_version ?? '—' }}</dd></div>
          <div><dt>配置版本</dt><dd>{{ report.meta.config_version ?? '—' }}</dd></div>
          <div><dt>开始时间</dt><dd>{{ formatDateTime(report.meta.started_at) }}</dd></div>
          <div><dt>完成时间</dt><dd>{{ formatDateTime(report.meta.completed_at) }}</dd></div>
        </dl>
      </ElCard>
    </template>
  </section>
</template>

<style scoped>
.report-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.report-state {
  margin: 0;
}

.report-state__title {
  margin: 12px 0 4px;
  font-size: 16px;
  color: var(--m1-text-primary);
}

.report-state__hint,
.report-state__meta {
  margin: 4px 0 0;
  color: var(--m1-text-secondary);
  font-size: 13px;
}

.report-code {
  margin: 8px 0;
  color: var(--m1-text-secondary);
  font-size: 12px;
}

.report-hero__eyebrow {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  color: var(--m1-text-secondary);
  font-size: 13px;
}

.report-hero__score {
  margin: 12px 0 0;
  color: var(--m1-text-primary);
  font-size: 56px;
  font-weight: 650;
  line-height: 1;
  font-variant-numeric: tabular-nums;
}

.report-hero__score--missing {
  font-size: 40px;
  color: var(--m1-text-secondary);
}

.report-hero__unit {
  color: var(--m1-text-secondary);
  font-size: 18px;
  font-weight: 400;
}

.report-hero__meta {
  margin: 10px 0 0;
  color: var(--m1-text-secondary);
  font-size: 13px;
}

.report-hero__note {
  max-width: 320px;
  margin: 0;
  color: var(--m1-text-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.report-grid {
  display: grid;
  grid-template-columns: minmax(280px, 1fr) minmax(320px, 1.4fr);
  gap: 16px;
}

@media (max-width: 860px) {
  .report-grid {
    grid-template-columns: 1fr;
  }
}

.report-block__title {
  margin: 0;
  color: var(--m1-text-primary);
  font-size: 16px;
  font-weight: 600;
}

.dimension-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.dimension-card {
  padding: 12px 14px;
  border: 1px solid #263244;
  border-radius: 10px;
  background: var(--m1-bg-subtle);
}

.dimension-card__header {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.dimension-card__header h3 {
  margin: 0;
  color: var(--m1-text-primary);
  font-size: 15px;
}

.dimension-card__score {
  color: var(--m1-info);
  font-size: 20px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
}

.dimension-card__weight {
  margin-left: auto;
  color: var(--m1-text-secondary);
  font-size: 12px;
}

.dimension-card__subs {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 6px 18px;
  margin: 10px 0 0;
}

.dimension-card__sub {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.dimension-card__sub dt {
  color: var(--m1-text-secondary);
  font-size: 12px;
}

.dimension-card__sub dd {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  color: var(--m1-text-regular);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.dimension-card__sub :deep(.el-progress) {
  width: 70px;
}

.suggestion-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.suggestion {
  padding: 12px 14px;
  border: 1px solid #263244;
  border-radius: 10px;
  background: var(--m1-bg-subtle);
}

.suggestion__header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.suggestion__rank {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--m1-border);
  color: var(--m1-info);
  font-size: 12px;
}

.suggestion__time {
  color: var(--m1-text-secondary);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.suggestion__evidence,
.suggestion__action {
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 1.65;
}

.suggestion__evidence {
  color: var(--m1-text-regular);
}

.suggestion__action {
  color: var(--m1-info);
}

.report-reasons {
  margin: 8px 0 0;
  padding-left: 20px;
  color: var(--m1-text-regular);
  font-size: 13px;
  line-height: 1.8;
}

.report-meta__grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 12px 20px;
  margin: 0;
}

.report-meta__grid dt {
  color: var(--m1-text-secondary);
  font-size: 12px;
}

.report-meta__grid dd {
  margin: 4px 0 0;
  color: var(--m1-text-regular);
  font-size: 13px;
  word-break: break-all;
}

.report-alert {
  margin: 0;
}
</style>
