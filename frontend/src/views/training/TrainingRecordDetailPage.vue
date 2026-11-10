<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDescriptions,
  ElDescriptionsItem,
  ElMessage,
  ElMessageBox,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus'
import { ApiError } from '../../types/api'
import { deleteTrainingRecord, getTrainingRecord, getTrainingReport } from './api'
import {
  dimensionLabel,
  evaluationLabel,
  formatDateTime,
  levelLabel,
} from './format'
import type { TrainingRecordView, TrainingReport } from './types'

/** Record detail / report entry (08 §10.6, §12.7); report is self-only. */

const route = useRoute()
const router = useRouter()
const recordId = computed(() => String(route.params.recordId ?? ''))

const record = ref<TrainingRecordView | null>(null)
const report = ref<TrainingReport | null>(null)
const loading = ref(true)
const errorMessage = ref('')
let pollTimer: ReturnType<typeof setInterval> | null = null

const TERMINAL = new Set(['completed', 'partial', 'failed'])
const evaluating = computed(() =>
  record.value !== null && !TERMINAL.has(record.value.evaluation_status),
)

async function load(): Promise<void> {
  try {
    record.value = await getTrainingRecord(recordId.value)
    errorMessage.value = ''
    if (record.value.report_available) {
      await loadReport()
    }
    if (!evaluating.value) {
      stopPolling()
    }
  } catch (error) {
    errorMessage.value = error instanceof ApiError
      ? error.message
      : '训练记录加载失败。'
    stopPolling()
  } finally {
    loading.value = false
  }
}

async function loadReport(): Promise<void> {
  try {
    report.value = await getTrainingReport(recordId.value)
  } catch (error) {
    if (error instanceof ApiError
      && error.code !== 'EVALUATION_NOT_READY'
      && error.code !== 'EVALUATION_FAILED') {
      errorMessage.value = error.message
    }
  }
}

function startPolling(): void {
  if (pollTimer) {
    return
  }
  pollTimer = setInterval(() => {
    if (evaluating.value) {
      void load()
    }
  }, 5_000)
}

function stopPolling(): void {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function back(): void {
  void router.push('/student/training/records')
}

async function removeRecord(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '删除后报告与回放立即不可访问。',
      '删除训练记录',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await deleteTrainingRecord(recordId.value)
    ElMessage.success('记录已删除')
    await router.push('/student/training/records')
  } catch (error) {
    errorMessage.value = error instanceof ApiError
      ? error.message
      : '删除失败，请重试。'
  }
}

onMounted(async () => {
  await load()
  startPolling()
})

onBeforeUnmount(stopPolling)
</script>

<template>
  <div class="record-detail">
    <div class="record-detail__top">
      <ElButton text @click="back">← 训练记录</ElButton>
      <ElButton
        v-if="record && record.status !== 'expired'"
        type="danger"
        text
        @click="removeRecord"
      >
        删除记录
      </ElButton>
    </div>

    <ElAlert v-if="errorMessage" type="error" :closable="false" show-icon>
      <template #title>{{ errorMessage }}</template>
    </ElAlert>

    <p v-if="loading" class="state">加载中…</p>

    <template v-else-if="record">
      <ElCard shadow="never">
        <div class="record-head">
          <div>
            <h1>{{ record.topic }}</h1>
            <p>
              训练 · {{ levelLabel(record.training_level) }} ·
              选中于 {{ formatDateTime(record.selected_at) }}
            </p>
          </div>
          <div class="record-head__tags">
            <ElTag effect="dark" type="warning">
              训练 · {{ levelLabel(record.training_level) }}
            </ElTag>
            <ElTag :type="record.status === 'expired' ? 'info' : 'warning'">
              {{ evaluationLabel(record.evaluation_status) }}
            </ElTag>
          </div>
        </div>

        <ElDescriptions :column="3" border>
          <ElDescriptionsItem label="到期时间">{{ formatDateTime(record.expires_at) }}</ElDescriptionsItem>
          <ElDescriptionsItem label="剩余天数">
            {{ record.status === 'expired' ? '已过期' : `${record.remaining_days} 天` }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="最终得分">
            {{ record.total_score ?? '—' }}
          </ElDescriptionsItem>
        </ElDescriptions>
      </ElCard>

      <ElCard shadow="never">
        <template #header><h2>训练回放</h2></template>
        <div class="replay-placeholder">
          训练回放复用 05B 布局；M3 训练 session 的回放读取开放后在此挂载。
        </div>
      </ElCard>

      <ElCard v-if="evaluating" shadow="never">
        <ElAlert type="info" :closable="false" show-icon title="评测进行中">
          报告生成后会自动刷新；评测完成前不影响记录保留。
        </ElAlert>
      </ElCard>

      <ElCard v-else-if="record.evaluation_status === 'failed'" shadow="never">
        <ElAlert type="error" :closable="false" show-icon title="评测失败">
          可删除本条记录后重新训练。
        </ElAlert>
      </ElCard>

      <template v-else-if="report">
        <ElCard shadow="never">
          <template #header><h2>评测报告</h2></template>
          <div class="score-row">
            <span>总分</span>
            <strong>{{ report.total_score ?? '—' }}</strong>
            <ElTag type="success">{{ evaluationLabel(report.status) }}</ElTag>
          </div>

          <ElTable :data="report.dimensions" stripe>
            <ElTableColumn label="维度" min-width="140">
              <template #default="{ row }">{{ dimensionLabel(row.dimension) }}</template>
            </ElTableColumn>
            <ElTableColumn prop="score" label="得分" width="100" />
            <ElTableColumn label="置信度" width="100">
              <template #default="{ row }">
                {{ row.confidence === null ? '—' : `${Math.round(row.confidence * 100)}%` }}
              </template>
            </ElTableColumn>
          </ElTable>
        </ElCard>

        <ElCard v-if="report.issues.length" shadow="never">
          <template #header><h2>问题与建议</h2></template>
          <ul class="issue-list">
            <li v-for="(issue, index) in report.issues" :key="`${issue.dimension}-${index}`">
              <div class="issue-list__head">
                <ElTag :type="issue.severity === 'high' ? 'danger' : 'warning'">
                  {{ dimensionLabel(issue.dimension) }}
                </ElTag>
                <span v-if="issue.start_ms !== null" class="issue-list__time">
                  {{ Math.round(issue.start_ms / 1000) }}s
                  <template v-if="issue.end_ms !== null">
                    – {{ Math.round(issue.end_ms / 1000) }}s
                  </template>
                </span>
              </div>
              <p>{{ issue.evidence }}</p>
              <p v-if="issue.suggestion" class="issue-list__suggestion">
                建议：{{ issue.suggestion }}
              </p>
            </li>
          </ul>
        </ElCard>

        <ElCard v-if="report.warnings.length" shadow="never">
          <template #header><h2>提示</h2></template>
          <ul class="warning-list">
            <li v-for="warning in report.warnings" :key="warning">{{ warning }}</li>
          </ul>
        </ElCard>
      </template>
    </template>
  </div>
</template>

<style scoped>
.record-detail {
  max-width: 1000px;
  margin: 0 auto;
  padding: 24px;
  display: grid;
  gap: 16px;
}

.record-detail__top {
  display: flex;
  justify-content: space-between;
}

.state {
  color: #8fa3c0;
}

.record-head {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.record-head h1 {
  margin: 0 0 6px;
}

.record-head p {
  margin: 0;
  color: #8fa3c0;
}

.record-head__tags {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}

.replay-placeholder {
  min-height: 160px;
  display: grid;
  place-items: center;
  color: #8fa3c0;
  border: 1px dashed rgba(143, 163, 192, 0.35);
  border-radius: 10px;
  padding: 16px;
  text-align: center;
}

.score-row {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 12px;
  color: #8fa3c0;
}

.score-row strong {
  color: #f5a524;
  font-size: 32px;
}

.issue-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 12px;
}

.issue-list li {
  border-left: 3px solid rgba(245, 165, 36, 0.55);
  padding-left: 12px;
}

.issue-list__head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.issue-list__time {
  color: #8fa3c0;
  font-size: 12px;
}

.issue-list p {
  margin: 6px 0 0;
}

.issue-list__suggestion {
  color: #8fa3c0;
}

.warning-list {
  margin: 0;
  padding-left: 18px;
  color: #8fa3c0;
}
</style>
