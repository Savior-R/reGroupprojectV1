<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton, ElCard, ElMessage, ElMessageBox, ElTag } from 'element-plus'
import { ApiError } from '../../types/api'
import { getTrainingSession, selectTrainingRecord } from './api'
import { formatDuration, levelLabel } from './format'
import type { TrainingSessionView } from './types'

/** Review / select (08 §12.6); at most three active records per template or free group. */

const route = useRoute()
const router = useRouter()
const sessionId = computed(() => String(route.params.sessionId ?? ''))

const session = ref<TrainingSessionView | null>(null)
const loading = ref(true)
const busy = ref(false)
const errorMessage = ref('')

const selectable = computed(() =>
  session.value?.status === 'ended' || session.value?.status === 'reviewing',
)

async function load(): Promise<void> {
  try {
    session.value = await getTrainingSession(sessionId.value)
  } catch (error) {
    errorMessage.value = error instanceof ApiError
      ? error.message
      : '训练回看加载失败。'
  } finally {
    loading.value = false
  }
}

async function keepTraining(): Promise<void> {
  busy.value = true
  errorMessage.value = ''
  try {
    await ElMessageBox.confirm(
      '保留这次训练会生成评测记录。每个模板 / 自由训练最多保留 3 条，超出将删除最旧记录。',
      '保留这次训练',
      { confirmButtonText: '保留', cancelButtonText: '取消', type: 'warning' },
    )
    const selection = await selectTrainingRecord(sessionId.value)
    if (selection.deleted_record_id) {
      ElMessage.info('已自动清理最旧的一条训练记录。')
    }
    await router.push(`/student/training/records/${selection.record.record_id}`)
  } catch (error) {
    if (error === 'cancel' || error === 'close') {
      return
    }
    errorMessage.value = error instanceof ApiError
      ? error.message
      : '保留训练失败，请重试。'
  } finally {
    busy.value = false
  }
}

async function discard(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '本次训练将不生成记录，临时录像按课堂临时录像规则清理。',
      '删除本次',
      { confirmButtonText: '删除本次', cancelButtonText: '取消', type: 'warning' },
    )
    ElMessage.info('本次训练未保留。')
    await router.push('/student/training')
  } catch {
    // User cancelled the confirmation.
  }
}

function backHome(): void {
  void router.push('/student/training')
}

onMounted(load)
</script>

<template>
  <div class="review-page">
    <div class="review-page__top">
      <ElButton text @click="backHome">← 返回训练</ElButton>
      <ElTag v-if="session" effect="dark" type="warning">
        训练 · {{ levelLabel(session.training_level) }}
      </ElTag>
    </div>

    <ElAlert v-if="errorMessage" type="error" :closable="false" show-icon>
      <template #title>{{ errorMessage }}</template>
    </ElAlert>

    <p v-if="loading" class="state">加载中…</p>

    <template v-else-if="session">
      <ElCard shadow="never">
        <div class="review-header">
          <div>
            <h1>{{ session.public_config.topic }}</h1>
            <p>
              训练 · {{ levelLabel(session.training_level) }} ·
              {{ formatDuration(session.max_duration_ms) }}
            </p>
          </div>
          <ElTag :type="selectable ? 'warning' : 'info'">
            {{ selectable ? '本次训练尚未保留' : session.status }}
          </ElTag>
        </div>

        <div class="replay-shell">
          <div class="replay-placeholder">
            <h2>回放 A · 屏幕主画面 + 摄像头小窗 + 事件流</h2>
            <p>
              训练回放复用 05B 布局；M3 训练 session 的回放读取开放后在此挂载。
              当前可先完成选中与评测。
            </p>
          </div>
        </div>
      </ElCard>

      <ElCard shadow="never" class="actions-card">
        <p class="limit-note">每个模板 / 自由训练最多保留 3 条，超出将删除最旧记录。</p>
        <div class="actions">
          <ElButton :disabled="busy || !selectable" @click="discard">删除本次</ElButton>
          <ElButton
            type="warning"
            :loading="busy"
            :disabled="!selectable"
            @click="keepTraining"
          >
            保留这次训练
          </ElButton>
        </div>
      </ElCard>
    </template>
  </div>
</template>

<style scoped>
.review-page {
  max-width: 1080px;
  margin: 0 auto;
  padding: 24px;
  display: grid;
  gap: 16px;
}

.review-page__top {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.state {
  color: #8fa3c0;
}

.review-header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.review-header h1 {
  margin: 0 0 6px;
}

.review-header p {
  margin: 0;
  color: #8fa3c0;
}

.replay-shell {
  min-height: 320px;
  border-radius: 12px;
  background: #0b1220;
  border: 1px solid rgba(143, 163, 192, 0.18);
  display: grid;
  place-items: center;
}

.replay-placeholder {
  max-width: 520px;
  padding: 24px;
  text-align: center;
  color: #8fa3c0;
}

.replay-placeholder h2 {
  color: #e8eef9;
  font-size: 16px;
}

.actions-card {
  border-radius: 12px;
}

.limit-note {
  margin: 0 0 12px;
  color: #8fa3c0;
  font-size: 12px;
}

.actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}
</style>
