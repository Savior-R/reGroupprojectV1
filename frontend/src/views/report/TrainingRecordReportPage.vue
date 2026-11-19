<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElAlert, ElButton, ElCard } from 'element-plus'
import { ApiError } from '../../types/api'
import { getEvaluationByTrainingRecord } from './api'
import EvaluationReportPanel from './EvaluationReportPanel.vue'

/**
 * Route entry for `/training/records/:recordId/report` (M6 training record).
 *
 * Training reports are private to the owning student, so a 403 is rendered as
 * an explicit permission state rather than a generic error.
 */

const route = useRoute()

const recordId = computed(() => String(route.params.recordId ?? ''))
const evaluationId = ref('')
const loading = ref(true)
const errorMessage = ref('')
const errorCode = ref('')
const forbidden = ref(false)
const notFound = ref(false)

async function resolve(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  forbidden.value = false
  notFound.value = false
  try {
    const evaluation = await getEvaluationByTrainingRecord(recordId.value)
    evaluationId.value = evaluation.evaluation_id
  } catch (error) {
    if (error instanceof ApiError) {
      errorCode.value = error.code
      errorMessage.value = error.message
      forbidden.value = error.status === 403
      notFound.value = error.status === 404
    } else {
      errorMessage.value = '训练报告加载失败，请稍后重试。'
    }
  } finally {
    loading.value = false
  }
}

onMounted(resolve)
</script>

<template>
  <div class="report-page">
    <header class="report-page__header">
      <div>
        <h1 class="report-page__title">训练评测报告</h1>
        <p class="report-page__hint">训练记录仅本人可见。</p>
      </div>
      <router-link class="report-page__back" :to="`/student/training/records/${recordId}`">
        返回训练记录
      </router-link>
    </header>

    <p v-if="loading" class="report-page__state">正在定位评测记录…</p>

    <ElAlert
      v-else-if="errorMessage"
      type="error"
      :closable="false"
      show-icon
    >
      <template #title>
        {{ forbidden ? '无权查看该训练报告' : notFound ? '该训练记录还没有评测' : errorMessage }}
      </template>
      <p v-if="errorCode" class="report-page__code">错误码：{{ errorCode }}</p>
      <ElButton v-if="!forbidden && !notFound" size="small" @click="resolve">重试</ElButton>
    </ElAlert>

    <EvaluationReportPanel v-else-if="evaluationId" :evaluation-id="evaluationId" />

    <ElCard v-else shadow="never">
      <p class="report-page__state">未找到训练评测记录。</p>
    </ElCard>
  </div>
</template>

<style scoped>
.report-page {
  max-width: 1120px;
  margin: 0 auto;
  padding: 24px;
}

.report-page__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
}

.report-page__title {
  margin: 0;
  color: var(--m1-text-primary);
  font-family: var(--m1-font-serif);
  font-size: 24px;
}

.report-page__hint {
  margin: 6px 0 0;
  color: var(--m1-text-secondary);
  font-size: 13px;
}

.report-page__back {
  color: var(--m1-primary);
  font-size: 13px;
  text-decoration: none;
  white-space: nowrap;
}

.report-page__state {
  margin: 0;
  color: var(--m1-text-secondary);
}

.report-page__code {
  margin: 8px 0;
  color: var(--m1-text-secondary);
  font-size: 12px;
}
</style>
