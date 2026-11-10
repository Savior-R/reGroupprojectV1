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
  ElTabPane,
  ElTabs,
  ElTag,
} from 'element-plus'
import { ApiError } from '../../types/api'
import { deleteTrainingRecord, listTrainingRecords } from './api'
import { evaluationLabel, formatDateTime, levelLabel } from './format'
import type { TrainingRecordView } from './types'

/** Training records (08 §12.7): all / evaluating / completed / expired. */

type RecordTab = 'all' | 'pending' | 'completed' | 'expired'

const router = useRouter()
const records = ref<TrainingRecordView[]>([])
const activeTab = ref<RecordTab>('all')
const loading = ref(true)
const errorMessage = ref('')

const READY = new Set(['completed', 'partial'])
const TERMINAL = new Set(['completed', 'partial', 'failed'])

const filtered = computed(() => {
  switch (activeTab.value) {
    case 'pending':
      return records.value.filter(
        (record) => record.status !== 'expired' && !TERMINAL.has(record.evaluation_status),
      )
    case 'completed':
      return records.value.filter((record) => READY.has(record.evaluation_status))
    case 'expired':
      return records.value.filter((record) => record.status === 'expired')
    default:
      return records.value.filter((record) => record.status !== 'expired')
  }
})

function statusType(record: TrainingRecordView): 'success' | 'warning' | 'info' | 'danger' {
  if (record.status === 'expired') {
    return 'info'
  }
  if (READY.has(record.evaluation_status)) {
    return 'success'
  }
  if (record.evaluation_status === 'failed') {
    return 'danger'
  }
  return 'warning'
}

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const page = await listTrainingRecords(1, 50)
    records.value = page.items
  } catch (error) {
    errorMessage.value = error instanceof ApiError
      ? error.message
      : '训练记录加载失败，请重试。'
  } finally {
    loading.value = false
  }
}

function openDetail(record: TrainingRecordView): void {
  void router.push(`/student/training/records/${record.record_id}`)
}

function backHome(): void {
  void router.push('/student/training')
}

async function removeRecord(record: TrainingRecordView): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '删除后报告与回放立即不可访问，记录将在后台清理。',
      '删除训练记录',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await deleteTrainingRecord(record.record_id)
    ElMessage.success('记录已删除')
    await load()
  } catch (error) {
    errorMessage.value = error instanceof ApiError
      ? error.message
      : '删除失败，请重试。'
  }
}

onMounted(load)
</script>

<template>
  <div class="records-page">
    <div class="records-page__top">
      <ElButton text @click="backHome">← 返回训练</ElButton>
      <ElButton @click="load">刷新</ElButton>
    </div>

    <header>
      <h1>训练记录</h1>
      <p>仅本人可见；每条记录保留 1 个月。</p>
    </header>

    <ElAlert v-if="errorMessage" type="error" :closable="false" show-icon>
      <template #title>{{ errorMessage }}</template>
    </ElAlert>

    <ElTabs v-model="activeTab">
      <ElTabPane label="全部" name="all" />
      <ElTabPane label="评测中" name="pending" />
      <ElTabPane label="已完成" name="completed" />
      <ElTabPane label="已过期" name="expired" />
    </ElTabs>

    <p v-if="loading" class="state">加载中…</p>
    <ElEmpty v-else-if="filtered.length === 0" description="暂无训练记录" />

    <section v-else class="record-list">
      <ElCard v-for="record in filtered" :key="record.record_id" shadow="hover">
        <div class="record-card">
          <div class="record-card__main">
            <div class="record-card__title">
              <h2>{{ record.topic }}</h2>
              <ElTag type="warning" effect="plain">{{ levelLabel(record.training_level) }}</ElTag>
              <ElTag :type="statusType(record)">{{ evaluationLabel(record.evaluation_status) }}</ElTag>
              <ElTag v-if="record.status === 'expired'" type="info">已过期</ElTag>
            </div>
            <p class="record-card__meta">
              {{ formatDateTime(record.selected_at) }}
              <span v-if="record.total_score !== null">· 完成度 {{ record.total_score }}</span>
              <span v-if="record.status !== 'expired'">· 剩 {{ record.remaining_days }} 天</span>
            </p>
          </div>
          <div class="record-card__actions">
            <ElButton size="small" @click="openDetail(record)">查看详情</ElButton>
            <ElButton
              v-if="record.report_available"
              size="small"
              type="primary"
              @click="openDetail(record)"
            >
              查看报告
            </ElButton>
            <ElButton
              v-if="record.status !== 'expired'"
              size="small"
              type="danger"
              text
              @click="removeRecord(record)"
            >
              删除
            </ElButton>
          </div>
        </div>
      </ElCard>
    </section>
  </div>
</template>

<style scoped>
.records-page {
  max-width: 960px;
  margin: 0 auto;
  padding: 24px;
  display: grid;
  gap: 16px;
}

.records-page__top {
  display: flex;
  justify-content: space-between;
}

.records-page header h1 {
  margin: 0 0 6px;
}

.records-page header p {
  margin: 0;
  color: #8fa3c0;
}

.state {
  color: #8fa3c0;
}

.record-list {
  display: grid;
  gap: 12px;
}

.record-card {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
}

.record-card__title {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.record-card__title h2 {
  margin: 0;
  font-size: 17px;
}

.record-card__meta {
  margin: 6px 0 0;
  color: #8fa3c0;
  font-size: 12px;
}

.record-card__actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}
</style>
