<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElButton } from 'element-plus'
import EvaluationReportPanel from './EvaluationReportPanel.vue'

/**
 * Route entry for `/evaluations/:evaluationId/report`.
 *
 * The route itself is registered by the Score/QA owner; this page only owns
 * the report body and the back link.
 */

const route = useRoute()
const router = useRouter()

const evaluationId = computed(() => String(route.params.evaluationId ?? ''))
const backTarget = computed(() => {
  const raw = route.query.from
  const value = Array.isArray(raw) ? raw[0] : raw
  return typeof value === 'string' && value.startsWith('/') ? value : '/student/tasks'
})

function goBack(): void {
  void router.push(backTarget.value)
}
</script>

<template>
  <div class="report-page">
    <header class="report-page__header">
      <div>
        <h1 class="report-page__title">AI 评测报告</h1>
        <p class="report-page__hint">内容、表达、互动、板书、时间的维度得分与改进建议。</p>
      </div>
      <ElButton text @click="goBack">返回</ElButton>
    </header>

    <EvaluationReportPanel v-if="evaluationId" :evaluation-id="evaluationId" />
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
</style>
