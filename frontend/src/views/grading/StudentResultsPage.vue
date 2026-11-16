<script setup lang="ts">
import { ref } from 'vue'
import { ElAlert, ElButton, ElCard, ElEmpty, ElPagination, ElTable, ElTableColumn, ElTag } from 'element-plus'
import M7PageHeader from './M7PageHeader.vue'
import { listStudentResults } from './api'
import type { StudentResultList } from './api'
import { formatScore, formatTime, statusLabel, statusTagType } from './ui'
import { ApiError } from '../../types/api'

/** Student "my results" list (07 §13.8). Students only see their own rows. */

const data = ref<StudentResultList | null>(null)
const loading = ref(true)
const errorMessage = ref('')
const errorCode = ref('')
const page = ref(1)
const size = ref(20)

async function load(showLoading = true): Promise<void> {
  if (showLoading) {
    loading.value = true
  }
  errorMessage.value = ''
  errorCode.value = ''
  try {
    data.value = await listStudentResults(page.value, size.value)
  } catch (error) {
    if (error instanceof ApiError) {
      errorMessage.value = error.message
      errorCode.value = error.code
    } else {
      errorMessage.value = '成绩列表加载失败，请重试。'
    }
  } finally {
    loading.value = false
  }
}

function changePage(next: number): void {
  page.value = next
  void load()
}

void load()
</script>

<template>
  <div class="m7-page">
    <M7PageHeader title="我的成绩" subtitle="只显示本人提交件的 AI 分、教师分、最终分与评语" />

    <ElAlert v-if="errorMessage" type="error" :closable="false" show-icon class="m7-alert">
      <template #title>{{ errorMessage }}</template>
      <p v-if="errorCode" class="m7-error-code">错误码：{{ errorCode }}</p>
      <p v-if="errorCode === 'FORBIDDEN'" class="m7-error-code">请切换到学生视角后重试。</p>
      <ElButton size="small" @click="load()">重试</ElButton>
    </ElAlert>

    <p v-else-if="loading" class="m7-state">加载中…</p>

    <ElCard v-else shadow="never" class="m7-card">
      <ElTable :data="data?.items ?? []" stripe class="m7-table">
        <ElTableColumn prop="task_title" label="任务" min-width="200">
          <template #default="{ row }">
            <router-link :to="`/student/results/${row.submission_id}`">{{ row.task_title }}</router-link>
          </template>
        </ElTableColumn>
        <ElTableColumn prop="course_name" label="课程" min-width="160" />
        <ElTableColumn label="提交时间" width="180">
          <template #default="{ row }">{{ formatTime(row.submitted_at) }}</template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="130">
          <template #default="{ row }">
            <ElTag :type="statusTagType(row.status)" size="small">{{ statusLabel(row.status) }}</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="最终分" width="110" align="right">
          <template #default="{ row }">
            <strong>{{ formatScore(row.final_total) }}</strong>
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <router-link :to="`/student/results/${row.submission_id}`">详情</router-link>
          </template>
        </ElTableColumn>
      </ElTable>
      <ElEmpty v-if="(data?.items.length ?? 0) === 0" description="暂无成绩记录" />
      <ElPagination
        v-if="data && data.total > 0"
        class="m7-pagination"
        layout="prev, pager, next, total"
        :current-page="page"
        :page-size="size"
        :total="data.total"
        @current-change="changePage"
      />
    </ElCard>
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
