<script setup lang="ts">
import { ref } from 'vue'
import { ElAlert, ElTabPane, ElTabs } from 'element-plus'
import QuestionBankPage from './QuestionBankPage.vue'
import TeacherTrainingTemplatesPage from '../training/TeacherTrainingTemplatesPage.vue'

/** Dev-only UI preview for M5 + M6 teacher pages, backed by `mockApi.ts`. */

const tab = ref('question')
</script>

<template>
  <div class="preview-shell">
    <header class="preview-header">
      <div>
        <h1>M5 题库 / M6 训练模板 · 测试预览</h1>
        <p>离线 Mock 数据，用于验证页面交互；不会写入数据库，也不代表后端联调结果。</p>
      </div>
      <span class="preview-badge">DEV PREVIEW</span>
    </header>

    <ElAlert type="warning" :closable="false" show-icon>
      <template #title>
        后端未启动（本机无 PostgreSQL），当前页面由前端 Mock API 驱动。真实接口、
        权限、清理与评测需在 Compose 环境复测。
      </template>
    </ElAlert>

    <ElTabs v-model="tab" class="preview-tabs">
      <ElTabPane label="M5 题库管理" name="question">
        <QuestionBankPage />
      </ElTabPane>
      <ElTabPane label="M6 训练模板" name="training">
        <TeacherTrainingTemplatesPage />
      </ElTabPane>
    </ElTabs>
  </div>
</template>

<style scoped>
.preview-shell {
  min-height: 100vh;
  padding: 20px 24px 40px;
  background: var(--el-bg-color-page);
}

.preview-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  max-width: 1180px;
  margin: 0 auto 12px;
}

.preview-header h1 {
  margin: 0 0 4px;
  font-size: 22px;
}

.preview-header p {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.preview-badge {
  padding: 4px 10px;
  border: 1px solid var(--el-color-warning);
  border-radius: 999px;
  color: var(--el-color-warning);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.08em;
  white-space: nowrap;
}

.preview-tabs {
  max-width: 1180px;
  margin: 16px auto 0;
}
</style>
