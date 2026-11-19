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
  ElTag,
} from 'element-plus'
import { ApiError } from '../../types/api'
import { deleteTrainingRecord, getTrainingRecord } from './api'
import { evaluationLabel, formatDateTime, levelLabel } from './format'
import type { TrainingRecordView } from './types'

/** Record detail / report entry (08 §10.6, §12.7); report is self-only. */

const route = useRoute()
const router = useRouter()
const recordId = computed(() => String(route.params.recordId ?? ''))

const record = ref<TrainingRecordView | null>(null)
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
        <p class="replay-hint">回放使用 M3 录制舞台：屏幕主画面 + 摄像头小窗 + 事件流。</p>
        <ElButton
          type="primary"
          plain
          @click="router.push(`/student/training/sessions/${record.session_id}/review`)"
        >
          查看回放
        </ElButton>
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

      <ElCard v-else-if="record.report_available" shadow="never">
        <template #header><h2>评测报告</h2></template>
        <div class="score-row">
          <span>总分</span>
          <strong>{{ record.total_score ?? '—' }}</strong>
          <ElTag type="success">{{ evaluationLabel(record.evaluation_status) }}</ElTag>
        </div>
        <p class="replay-hint">维度得分、置信度、问题时间线与改进建议在完整报告中查看。</p>
        <ElButton
          type="primary"
          @click="router.push(`/student/training/records/${recordId}/report`)"
        >
          查看完整评测报告
        </ElButton>
      </ElCard>
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
  color: var(--m1-text-secondary);
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
  color: var(--m1-text-secondary);
}

.record-head__tags {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}

.replay-hint {
  margin: 0 0 12px;
  color: var(--m1-text-secondary);
  font-size: 13px;
}

.score-row {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 12px;
  color: var(--m1-text-secondary);
}

.score-row strong {
  color: var(--m1-accent);
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
  color: var(--m1-text-secondary);
  font-size: 12px;
}

.issue-list p {
  margin: 6px 0 0;
}

.issue-list__suggestion {
  color: var(--m1-text-secondary);
}

.warning-list {
  margin: 0;
  padding-left: 18px;
  color: var(--m1-text-secondary);
}
</style>
