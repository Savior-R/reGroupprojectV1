<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  ElAlert,
  ElButton,
  ElCard,
  ElEmpty,
  ElOption,
  ElSelect,
  ElTable,
  ElTableColumn,
} from 'element-plus'
import M7PageHeader from '../grading/M7PageHeader.vue'
import { getCourseStats, listGradingSubmissions } from '../grading/api'
import type { CourseStatsResponse } from '../grading/api'
import { classOptions, formatScore, modeLabel } from '../grading/ui'
import { ApiError } from '../../types/api'

/**
 * Course statistics page (07 §13.7). Aggregates completed submissions with a
 * pooled average, not an average of task averages.
 */

const route = useRoute()
const courseId = computed(() => String(route.params.courseId ?? ''))

const stats = ref<CourseStatsResponse | null>(null)
const loading = ref(true)
const errorMessage = ref('')
const errorCode = ref('')
const classId = ref('')
const classChoices = ref<Array<{ value: string; label: string }>>([])

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  errorCode.value = ''
  try {
    stats.value = await getCourseStats(courseId.value, classId.value || undefined)
    if (stats.value.tasks.length > 0) {
      const roster = await listGradingSubmissions(stats.value.tasks[0].task_id, {
        page: 1,
        size: 100,
      })
      classChoices.value = classOptions(roster.items)
    } else {
      classChoices.value = []
    }
  } catch (error) {
    if (error instanceof ApiError) {
      errorMessage.value = error.message
      errorCode.value = error.code
    } else {
      errorMessage.value = '课程统计加载失败，请重试。'
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
      title="课程统计"
      subtitle="课程内所有已完成提交件的合并平均分；不跨课程"
      :course-id="courseId"
    />

    <ElAlert v-if="errorMessage" type="error" :closable="false" show-icon class="m7-alert">
      <template #title>{{ errorMessage }}</template>
      <p v-if="errorCode" class="m7-error-code">错误码：{{ errorCode }}</p>
      <ElButton size="small" @click="load">重试</ElButton>
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
            @change="load"
          >
            <ElOption
              v-for="item in classChoices"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </ElSelect>
          <ElButton @click="load">刷新</ElButton>
        </div>
        <div class="m7-summary">
          <div class="m7-summary__item"><span>任务数</span><strong>{{ stats.summary.tasks }}</strong></div>
          <div class="m7-summary__item"><span>分配</span><strong>{{ stats.summary.assigned }}</strong></div>
          <div class="m7-summary__item"><span>提交</span><strong>{{ stats.summary.submitted }}</strong></div>
          <div class="m7-summary__item"><span>已完成</span><strong>{{ stats.summary.completed }}</strong></div>
          <div class="m7-summary__item"><span>课程平均最终分</span><strong>{{ formatScore(stats.summary.average_final) }}</strong></div>
        </div>
      </ElCard>

      <ElCard shadow="never" class="m7-card m7-section">
        <template #header><strong>任务明细</strong></template>
        <ElTable :data="stats.tasks" stripe class="m7-table">
          <ElTableColumn prop="topic" label="任务" min-width="200" />
          <ElTableColumn label="模式" width="120">
            <template #default="{ row }">{{ modeLabel(row.mode) }}</template>
          </ElTableColumn>
          <ElTableColumn prop="assigned" label="分配" width="90" align="right" />
          <ElTableColumn prop="submitted" label="提交" width="90" align="right" />
          <ElTableColumn prop="completed" label="完成" width="90" align="right" />
          <ElTableColumn label="平均最终分" width="130" align="right">
            <template #default="{ row }">{{ formatScore(row.average_final) }}</template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="120" fixed="right">
            <template #default="{ row }">
              <router-link :to="`/teacher/courses/${courseId}/tasks/${row.task_id}/stats`">
                查看统计
              </router-link>
            </template>
          </ElTableColumn>
        </ElTable>
        <ElEmpty v-if="stats.tasks.length === 0" description="该课程暂无已发布任务" />
      </ElCard>
    </template>
  </div>
</template>

<style scoped>
.m7-page {
  max-width: 1100px;
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

.m7-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
}

.m7-summary {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
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

.m7-table {
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-header-bg-color: #111c33;
  --el-table-row-hover-bg-color: #17233c;
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
