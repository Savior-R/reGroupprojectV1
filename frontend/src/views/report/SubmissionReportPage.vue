<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton, ElCard } from 'element-plus'
import { ApiError } from '../../types/api'
import { getEvaluationBySubmission } from './api'
import EvaluationReportPanel from './EvaluationReportPanel.vue'

/**
 * Route entry for `/submissions/:submissionId/report`.
 *
 * Resolves the submission's evaluation first, then hands off to the shared
 * report panel (which owns polling and every terminal state).
 */

const route = useRoute()
const router = useRouter()

const submissionId = computed(() => String(route.params.submissionId ?? ''))
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
    const evaluation = await getEvaluationBySubmission(submissionId.value)
    evaluationId.value = evaluation.evaluation_id
  } catch (error) {
    if (error instanceof ApiError) {
      errorCode.value = error.code
      errorMessage.value = error.message
      forbidden.value = error.status === 403
      notFound.value = error.status === 404
    } else {
      errorMessage.value = '评测报告加载失败，请稍后重试。'
    }
  } finally {
    loading.value = false
  }
}

function goBack(): void {
  void router.push('/student/tasks')
}

onMounted(resolve)
</script>

<template>
  <div class="report-page">
    <header class="report-page__header">
      <div>
        <h1 class="report-page__title">AI 评测报告</h1>
        <p class="report-page__hint">按提交记录查看本次课堂的评测结果。</p>
      </div>
      <ElButton text @click="goBack">返回任务</ElButton>
    </header>

    <p v-if="loading" class="report-page__state">正在定位评测记录…</p>

    <ElAlert
      v-else-if="errorMessage"
      type="error"
      :closable="false"
      show-icon
    >
      <template #title>
        {{ forbidden ? '无权查看该评测报告' : notFound ? '该提交还没有评测记录' : errorMessage }}
      </template>
      <p v-if="errorCode" class="report-page__code">错误码：{{ errorCode }}</p>
      <ElButton v-if="!forbidden && !notFound" size="small" @click="resolve">重试</ElButton>
    </ElAlert>

    <EvaluationReportPanel v-else-if="evaluationId" :evaluation-id="evaluationId" />

    <ElCard v-else shadow="never">
      <p class="report-page__state">未找到评测记录。</p>
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
  color: #e5e7eb;
  font-size: 24px;
}

.report-page__hint {
  margin: 6px 0 0;
  color: #94a3b8;
  font-size: 13px;
}

.report-page__state {
  margin: 0;
  color: #94a3b8;
}

.report-page__code {
  margin: 8px 0;
  color: #94a3b8;
  font-size: 12px;
}
</style>
