<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  ElAlert,
  ElButton,
  ElCard,
  ElEmpty,
  ElMessage,
  ElOption,
  ElPagination,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus'
import M7PageHeader from '../grading/M7PageHeader.vue'
import {
  downloadTaskCsv,
  getLeaderboard,
  getTaskStats,
  listGradingSubmissions,
} from '../grading/api'
import type { GradingResponse, LeaderboardResponse, TaskStatsResponse } from '../grading/api'
import { classOptions, dimensionLabel, formatScore } from '../grading/ui'
import { ApiError } from '../../types/api'

/**
 * Task statistics and leaderboard page (07 §13.6).
 */

const route = useRoute()
const courseId = computed(() => String(route.params.courseId ?? ''))
const taskId = computed(() => String(route.params.taskId ?? ''))

const stats = ref<TaskStatsResponse | null>(null)
const leaderboard = ref<LeaderboardResponse | null>(null)
const roster = ref<GradingResponse | null>(null)
const loading = ref(true)
const errorMessage = ref('')
const errorCode = ref('')

const classId = ref('')
const page = ref(1)
const size = ref(20)
const exporting = ref(false)

const classChoices = computed(() =>
  roster.value ? classOptions(roster.value.items) : [],
)
const leaderboardTotal = computed(() => leaderboard.value?.total ?? 0)
const weakSet = computed(() => new Set(stats.value?.weak_dimensions ?? []))

async function load(showLoading = true): Promise<void> {
  if (showLoading) {
    loading.value = true
  }
  errorMessage.value = ''
  errorCode.value = ''
  try {
    const [nextStats, nextLeaderboard, nextRoster] = await Promise.all([
      getTaskStats(taskId.value, classId.value || undefined),
      getLeaderboard(taskId.value, {
        classId: classId.value || undefined,
        page: page.value,
        size: size.value,
      }),
      listGradingSubmissions(taskId.value, { page: 1, size: 100 }),
    ])
    stats.value = nextStats
    leaderboard.value = nextLeaderboard
    roster.value = nextRoster
  } catch (error) {
    if (error instanceof ApiError) {
      errorMessage.value = error.message
      errorCode.value = error.code
    } else {
      errorMessage.value = '统计加载失败，请重试。'
    }
  } finally {
    loading.value = false
  }
}

function applyClass(): void {
  page.value = 1
  void load()
}

function changePage(next: number): void {
  page.value = next
  void load(false)
}

function barWidth(value: number | null): string {
  if (value === null) {
    return '0%'
  }
  return `${Math.max(0, Math.min(100, value))}%`
}

async function exportCsv(): Promise<void> {
  exporting.value = true
  try {
    const download = await downloadTaskCsv(taskId.value, {
      classId: classId.value || undefined,
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
      title="任务统计"
      :subtitle="stats ? (stats.sample_sufficient ? '样本充足（已完成 ≥ 3）' : '样本不足：已完成少于 3 人') : undefined"
      :course-id="courseId"
      :task-id="taskId"
    />

    <ElAlert v-if="errorMessage" type="error" :closable="false" show-icon class="m7-alert">
      <template #title>{{ errorMessage }}</template>
      <p v-if="errorCode" class="m7-error-code">错误码：{{ errorCode }}</p>
      <ElButton size="small" @click="load()">重试</ElButton>
    </ElAlert>

    <p v-else-if="loading" class="m7-state">加载中…</p>

    <template v-else-if="stats">
      <ElCard shadow="never" class="m7-card">
        <div class="m7-toolbar">
          <ElSelect
            v-model="classId"
            placeholder="全部班级"
            clearable
            style="width: 180px"
            @change="applyClass"
          >
            <ElOption
              v-for="item in classChoices"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </ElSelect>
          <ElButton :loading="exporting" @click="exportCsv">导出 CSV</ElButton>
        </div>

        <div class="m7-summary">
          <div class="m7-summary__item"><span>分配</span><strong>{{ stats.summary.assigned }}</strong></div>
          <div class="m7-summary__item"><span>提交</span><strong>{{ stats.summary.submitted }}</strong></div>
          <div class="m7-summary__item"><span>已完成</span><strong>{{ stats.summary.completed }}</strong></div>
          <div class="m7-summary__item"><span>平均最终分</span><strong>{{ formatScore(stats.summary.average_final) }}</strong></div>
          <div class="m7-summary__item"><span>待 AI</span><strong>{{ stats.summary.pending_ai }}</strong></div>
          <div class="m7-summary__item"><span>待教师</span><strong>{{ stats.summary.pending_teacher }}</strong></div>
          <div class="m7-summary__item"><span>AI 不完整 / 失败</span><strong>{{ stats.summary.ai_partial + stats.summary.ai_failed }}</strong></div>
          <div class="m7-summary__item"><span>未提交 / 进行中</span><strong>{{ stats.summary.not_submitted }} / {{ stats.summary.in_progress }}</strong></div>
        </div>
      </ElCard>

      <ElCard shadow="never" class="m7-card m7-section">
        <template #header>
          <div class="m7-section__head">
            <strong>维度平均分</strong>
            <ElTag v-if="!stats.sample_sufficient" type="warning" size="small">样本不足</ElTag>
          </div>
        </template>
        <ElEmpty v-if="stats.summary.completed === 0" description="暂无可统计成绩" />
        <div v-else class="m7-bars">
          <div v-for="item in stats.dimensions" :key="item.dimension" class="m7-bar">
            <div class="m7-bar__label">
              <span>
                {{ dimensionLabel(item.dimension) }}
                <ElTag v-if="weakSet.has(item.dimension)" type="danger" size="small">薄弱</ElTag>
              </span>
              <span>
                {{ formatScore(item.average) }}（{{ item.sample_count }} 人）
                <ElTag v-if="item.sample_count > 0 && item.sample_count < 3" type="info" size="small">
                  样本不足
                </ElTag>
              </span>
            </div>
            <div class="m7-bar__track">
              <div
                class="m7-bar__fill"
                :class="{ 'm7-bar__fill--weak': item.average !== null && item.average < 60 }"
                :style="{ width: barWidth(item.average) }"
              />
            </div>
          </div>
        </div>
        <p v-if="stats.weak_dimensions.length" class="m7-weak">
          薄弱项：
          <span v-for="dimension in stats.weak_dimensions" :key="dimension">
            {{ dimensionLabel(dimension) }}
          </span>
        </p>
      </ElCard>

      <ElCard shadow="never" class="m7-card m7-section">
        <template #header>
          <div class="m7-section__head">
            <strong>排行榜</strong>
            <span class="m7-muted">仅统计已完成；同分按教师分、AI 分、账号 ID 排序</span>
          </div>
        </template>
        <ElTable :data="leaderboard?.items ?? []" stripe class="m7-table">
          <ElTableColumn prop="rank" label="名次" width="80" />
          <ElTableColumn prop="student_name" label="学生" min-width="140" />
          <ElTableColumn prop="class_name" label="班级" min-width="120" />
          <ElTableColumn label="AI" width="90" align="right">
            <template #default="{ row }">{{ formatScore(row.ai_total) }}</template>
          </ElTableColumn>
          <ElTableColumn label="教师" width="90" align="right">
            <template #default="{ row }">{{ formatScore(row.teacher_total) }}</template>
          </ElTableColumn>
          <ElTableColumn label="最终" width="100" align="right">
            <template #default="{ row }"><strong>{{ formatScore(row.final_total) }}</strong></template>
          </ElTableColumn>
        </ElTable>
        <ElEmpty v-if="(leaderboard?.items.length ?? 0) === 0" description="暂无排行榜数据" />
        <ElPagination
          v-if="leaderboardTotal > 0"
          class="m7-pagination"
          layout="prev, pager, next, total"
          :current-page="page"
          :page-size="size"
          :total="leaderboardTotal"
          @current-change="changePage"
        />
      </ElCard>

    </template>
  </div>
</template>

<style scoped>
.m7-page {
  max-width: 1200px;
  margin: 0 auto;
  padding: 24px;
  color: #e8eef9;
}

.m7-card {
  border: 1px solid #1f2b44;
  background: #0f1a2e;
}

.m7-section {
  margin-top: 16px;
}

.m7-section__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.m7-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.m7-summary {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(130px, 1fr));
  gap: 10px;
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
  color: #f5a524;
  font-size: 20px;
}

.m7-bars {
  display: grid;
  gap: 14px;
}

.m7-bar__label {
  display: flex;
  justify-content: space-between;
  margin-bottom: 6px;
  color: #8fa3c0;
  font-size: 13px;
}

.m7-bar__track {
  height: 10px;
  overflow: hidden;
  border-radius: 999px;
  background: #1a2742;
}

.m7-bar__fill {
  height: 100%;
  border-radius: 999px;
  background: linear-gradient(90deg, #f5a524, #ffc966);
}

.m7-bar__fill--weak {
  background: linear-gradient(90deg, #ff6b6b, #ff9b9b);
}

.m7-weak {
  margin: 16px 0 0;
  color: #ff9b9b;
  font-size: 13px;
}

.m7-weak span + span::before {
  content: ' / ';
  color: #8fa3c0;
}

.m7-table {
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-header-bg-color: #111c33;
  --el-table-row-hover-bg-color: #17233c;
}

.m7-pagination {
  margin-top: 16px;
  justify-content: flex-end;
}

.m7-muted {
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
