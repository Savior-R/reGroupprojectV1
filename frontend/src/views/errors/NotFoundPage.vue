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
      icon="info"
      title="404 未找到"
      sub-title="页面不存在或已被移动，请检查地址后重试。"
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