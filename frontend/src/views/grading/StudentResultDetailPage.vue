<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElAlert, ElButton, ElCard, ElTag } from 'element-plus'
import M7PageHeader from './M7PageHeader.vue'
import { getStudentResult } from './api'
import type { StudentResultDetail } from './api'
import {
  dimensionLabel,
  formatScore,
  formatTime,
  statusLabel,
  statusTagType,
} from './ui'
import { ApiError } from '../../types/api'

/**
 * Student result detail (07 §13.8). Pending rows show the status only; a
 * partial / failed AI evaluation shows the failure reason, never a half score.
 */

const route = useRoute()
const submissionId = computed(() => String(route.params.submissionId ?? ''))

const detail = ref<StudentResultDetail | null>(null)
const loading = ref(true)
const errorMessage = ref('')
const errorCode = ref('')

const completed = computed(() => detail.value?.status === 'completed')
const pending = computed(
  () => detail.value?.status === 'pending_ai' || detail.value?.status === 'pending_teacher',
)
const failed = computed(
  () => detail.value?.status === 'ai_partial' || detail.value?.status === 'ai_failed',
)

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  errorCode.value = ''
  try {
    detail.value = await getStudentResult(submissionId.value)
  } catch (error) {
    if (error instanceof ApiError) {
      errorMessage.value = error.message
      errorCode.value = error.code
    } else {
      errorMessage.value = '成绩加载失败，请重试。'
    }
  } finally {
    loading.value = false
  }
}

void load()
</script>

<template>
  <div class="m7-page">
    <M7PageHeader
      :title="detail?.task_title ?? '成绩详情'"
      :subtitle="detail ? `${detail.course_name} · 提交于 ${formatTime(detail.submitted_at)}` : undefined"
    />

    <ElAlert v-if="errorMessage" type="error" :closable="false" show-icon class="m7-alert">
      <template #title>{{ errorMessage }}</template>
      <p v-if="errorCode" class="m7-error-code">错误码：{{ errorCode }}</p>
      <ElButton size="small" @click="load">重试</ElButton>
    </ElAlert>

    <p v-else-if="loading" class="m7-state">加载中…</p>

    <template v-else-if="detail">
      <ElCard shadow="never" class="m7-card">
        <div class="m7-detail-head">
          <ElTag :type="statusTagType(detail.status)" size="large">
            {{ statusLabel(detail.status) }}
          </ElTag>
          <router-link to="/student/results">返回成绩列表</router-link>
        </div>

        <ElAlert v-if="pending" type="info" :closable="false" class="m7-alert"
          :title="detail.status === 'pending_ai' ? '待 AI 评测完成后生成最终分' : '待教师评分'" />
        <ElAlert v-if="failed" type="error" :closable="false" class="m7-alert"
          :title="`AI 评测未完整完成${detail.error_code ? `（${detail.error_code}）` : ''}`">
          <p>{{ detail.error_message || '暂不计算最终分，请稍后重试。' }}</p>
        </ElAlert>
        <ElAlert
          v-else-if="detail.error_code"
          type="warning"
          :closable="false"
          class="m7-alert"
          :title="`数据异常（${detail.error_code}）`"
        >
          <p>{{ detail.error_message || '上游 M3 / M4 数据尚未就绪。' }}</p>
        </ElAlert>

        <div class="m7-score-cards">
          <div class="m7-score-card">
            <span>AI 分</span>
            <strong>{{ formatScore(detail.ai_total) }}</strong>
          </div>
          <div class="m7-score-card">
            <span>教师分</span>
            <strong>{{ formatScore(detail.teacher_total) }}</strong>
          </div>
          <div class="m7-score-card m7-score-card--final">
            <span>最终分</span>
            <strong>{{ formatScore(detail.final_total) }}</strong>
          </div>
        </div>

        <template v-if="completed">
          <h2 class="m7-subtitle">最终维度分</h2>
          <div class="m7-dimensions">
            <div v-for="(score, dimension) in detail.final_dimensions" :key="dimension" class="m7-dimension">
              <span>{{ dimensionLabel(dimension) }}</span>
              <strong>{{ formatScore(score) }}</strong>
            </div>
          </div>

          <h2 class="m7-subtitle">教师评语</h2>
          <p class="m7-comment">{{ detail.teacher_comment || '暂无评语' }}</p>
        </template>

        <div class="m7-links">
          <a v-if="detail.ai_report_url" :href="detail.ai_report_url" target="_blank" rel="noreferrer">
            查看 AI 报告
          </a>
          <a v-if="detail.replay_url" :href="detail.replay_url" target="_blank" rel="noreferrer">
            查看回放
          </a>
        </div>
        <p class="m7-muted">只显示本人成绩；不提供班级排名或他人成绩。</p>
      </ElCard>
    </template>
  </div>
</template>

<style scoped>
.m7-page {
  max-width: 900px;
  margin: 0 auto;
  padding: 24px;
  color: #e8eef9;
}

.m7-card {
  border: 1px solid #1f2b44;
  background: #0f1a2e;
}

.m7-detail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.m7-score-cards {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin: 18px 0;
}

.m7-score-card {
  padding: 14px 16px;
  border: 1px solid #1f2b44;
  border-radius: 10px;
  background: #111c33;
}

.m7-score-card span {
  display: block;
  color: #8fa3c0;
  font-size: 12px;
}

.m7-score-card strong {
  font-size: 26px;
  color: #e8eef9;
}

.m7-score-card--final strong {
  color: #f5a524;
}

.m7-subtitle {
  margin: 20px 0 10px;
  font-size: 15px;
  color: #e8eef9;
}

.m7-dimensions {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 8px;
}

.m7-dimension {
  display: flex;
  justify-content: space-between;
  padding: 8px 10px;
  border-radius: 8px;
  background: #111c33;
  color: #8fa3c0;
  font-size: 13px;
}

.m7-dimension strong {
  color: #e8eef9;
}

.m7-comment {
  margin: 0;
  padding: 12px;
  border-radius: 8px;
  background: #111c33;
  color: #cbd5e1;
  white-space: pre-wrap;
}

.m7-links {
  display: flex;
  gap: 16px;
  margin-top: 20px;
}

.m7-links a {
  color: #f5a524;
}

.m7-muted {
  margin-top: 16px;
  color: #8fa3c0;
  font-size: 12px;
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
</style>
