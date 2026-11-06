<script setup lang="ts">
import { ElButton, ElResult } from 'element-plus'
import { useRouter } from 'vue-router'

const router = useRouter()

function goHome(): void {
  void router.push('/')
}
</script>

<template>
  <div class="error-page">
    <ElResult
      icon="warning"
      title="403 无权限"
      sub-title="你没有权限访问该页面。如需访问，请联系课程教师或管理员。"
    >
      <template #extra>
        <ElButton type="primary" @click="goHome">返回首页</ElButton>
      </template>
    </ElResult>
  </div>
</template>

<style scoped>
.error-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 60vh;
  color: #e5e7eb;
}
</style>