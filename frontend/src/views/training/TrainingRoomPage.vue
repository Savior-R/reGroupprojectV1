<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElAlert, ElButton, ElCard, ElTag } from 'element-plus'
import { ApiError } from '../../types/api'
import { cancelTrainingSession, endTrainingSession, getTrainingSession } from './api'
import { formatDuration, levelLabel } from './format'
import type { TrainingSessionView } from './types'

/**
 * Training room shell (08 §12.5).
 *
 * M6 owns the session record and the end / cancel transitions. The recording
 * stage itself reuses 05A/05B; M3 has not exposed training-session WS/recording
 * reads yet, so this page intentionally shows the public config and the M6
 * lifecycle rather than duplicating the task classroom.
 */

const route = useRoute()
const router = useRouter()
const sessionId = computed(() => String(route.params.sessionId ?? ''))

const session = ref<TrainingSessionView | null>(null)
const loading = ref(true)
const busy = ref(false)
const errorMessage = ref('')
let pollTimer: ReturnType<typeof setInterval> | null = null

const canEnd = computed(() =>
  session.value?.status === 'preparing' || session.value?.status === 'in_progress',
)
const canReview = computed(() =>
  session.value?.status === 'ended' || session.value?.status === 'reviewing',
)

async function load(): Promise<void> {
  try {
    session.value = await getTrainingSession(sessionId.value)
    errorMessage.value = ''
  } catch (error) {
    errorMessage.value = error instanceof ApiError
      ? error.message
      : '训练 session 加载失败。'
  } finally {
    loading.value = false
  }
}

async function endTraining(): Promise<void> {
  busy.value = true
  errorMessage.value = ''
  try {
    session.value = await endTrainingSession(sessionId.value)
    if (canReview.value) {
      await router.push(`/student/training/sessions/${sessionId.value}/review`)
    }
  } catch (error) {
    errorMessage.value = error instanceof ApiError
      ? error.message
      : '结束训练失败，请重试。'
  } finally {
    busy.value = false
  }
}

async function cancelTraining(): Promise<void> {
  busy.value = true
  errorMessage.value = ''
  try {
    await cancelTrainingSession(sessionId.value)
    await router.push('/student/training')
  } catch (error) {
    errorMessage.value = error instanceof ApiError
      ? error.message
      : '取消训练失败，请重试。'
  } finally {
    busy.value = false
  }
}

function openReview(): void {
  void router.push(`/student/training/sessions/${sessionId.value}/review`)
}

function backHome(): void {
  void router.push('/student/training')
}

onMounted(() => {
  void load()
  pollTimer = setInterval(() => void load(), 10_000)
})

onBeforeUnmount(() => {
  if (pollTimer) {
    clearInterval(pollTimer)
  }
})
</script>

<template>
  <div class="room-page">
    <header class="room-header">
      <div>
        <p class="room-header__eyebrow">训练课堂</p>
        <h1>{{ session?.public_config.topic ?? '训练' }}</h1>
        <p v-if="session">
          {{ levelLabel(session.training_level) }} ·
          {{ formatDuration(session.recommended_duration_ms) }} /
          最长 {{ formatDuration(session.max_duration_ms) }}
        </p>
      </div>
      <div class="room-header__badges">
        <ElTag v-if="session" effect="dark" type="warning">
          训练 · {{ levelLabel(session.training_level) }}
        </ElTag>
        <ElTag v-if="session" type="info">{{ session.status }}</ElTag>
      </div>
    </header>

    <ElAlert v-if="errorMessage" type="error" :closable="false" show-icon>
      <template #title>{{ errorMessage }}</template>
    </ElAlert>

    <p v-if="loading" class="state">加载中…</p>

    <template v-else-if="session">
      <ElAlert type="warning" :closable="false" show-icon title="训练录制接入中">
        M6 已完成训练 session、虚拟学生配置与结束 / 取消；实时课堂与三路录制复用 M3，
        M3 训练 session 的 WS / 录制读取开放后即可在本页挂载 05A/05B 舞台。
      </ElAlert>

      <section class="room-stage">
        <ElCard class="stage-share" shadow="never">
          <div class="stage-placeholder">
            <h2>共享窗口</h2>
            <p>训练课堂录制舞台</p>
          </div>
        </ElCard>
        <ElCard class="stage-side" shadow="never">
          <template #header><h2>虚拟学生</h2></template>
          <ul class="student-list">
            <li
              v-for="student in session.public_config.virtual_students"
              :key="`${student.seat_no}-${student.name}`"
            >
              <span class="student-avatar">{{ student.avatar }}</span>
              <span>{{ student.name }}</span>
              <span class="student-seat">座 {{ student.seat_no }}</span>
            </li>
          </ul>
        </ElCard>
      </section>

      <footer class="room-controls">
        <ElButton v-if="session.status === 'preparing'" :disabled="busy" @click="cancelTraining">
          取消训练
        </ElButton>
        <ElButton v-if="canReview" type="warning" @click="openReview">进入回看</ElButton>
        <ElButton v-if="canEnd" type="danger" :loading="busy" @click="endTraining">
          结束训练
        </ElButton>
        <ElButton
          v-if="session.status === 'cancelled' || session.status === 'failed'"
          @click="backHome"
        >
          返回训练首页
        </ElButton>
      </footer>
    </template>
  </div>
</template>

<style scoped>
.room-page {
  min-height: calc(100vh - 48px);
  padding: 24px;
  display: grid;
  gap: 16px;
  background: radial-gradient(circle at 20% 0%, #16264a 0, #0b1220 48%);
}

.room-header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
}

.room-header__eyebrow {
  margin: 0 0 4px;
  color: #f5a524;
  font-size: 12px;
  letter-spacing: 0.18em;
}

.room-header h1 {
  margin: 0 0 6px;
}

.room-header p {
  margin: 0;
  color: #8fa3c0;
}

.room-header__badges {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.state {
  color: #8fa3c0;
}

.room-stage {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: 16px;
}

.stage-share {
  min-height: 360px;
  border-radius: 12px;
}

.stage-placeholder {
  min-height: 320px;
  display: grid;
  place-content: center;
  text-align: center;
  color: #8fa3c0;
}

.stage-side {
  border-radius: 12px;
}

.stage-side h2,
.student-list {
  margin: 0;
}

.student-list {
  list-style: none;
  padding: 0;
  display: grid;
  gap: 8px;
}

.student-list li {
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 8px;
  padding: 8px;
  border-radius: 8px;
  background: rgba(143, 163, 192, 0.08);
}

.student-avatar {
  font-size: 11px;
  color: #f5a524;
}

.student-seat {
  color: #8fa3c0;
  font-size: 12px;
}

.room-controls {
  display: flex;
  justify-content: center;
  gap: 12px;
}

@media (max-width: 860px) {
  .room-stage {
    grid-template-columns: 1fr;
  }
}
</style>
