<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElAlert, ElEmpty, ElTag } from 'element-plus'
import { getTexts } from '../../api/replay'
import type { SessionText } from '../../api/replay'
import type { ClassroomStudentState } from '../../utils/classroomReducer'

/**
 * Live classroom dialogue panel (05A §6, 09 §6.5).
 *
 * Student/teacher text is persisted server-side and exposed by `/texts`; the
 * control events only carry text ids, so the panel refetches when the parent
 * bumps `refreshToken`. The live ASR tail is rendered separately until the
 * final row is persisted.
 */

const props = withDefaults(
  defineProps<{
    sessionId: string
    students: Record<string, ClassroomStudentState>
    liveText?: string
    refreshToken?: number
    active?: boolean
  }>(),
  {
    liveText: '',
    refreshToken: 0,
    active: true,
  },
)

const texts = ref<SessionText[]>([])
const loading = ref(false)
const error = ref('')
const scroller = ref<HTMLElement | null>(null)
let loadGeneration = 0

const visibleTexts = computed(() =>
  texts.value
    .filter((text) => text.text.trim().length > 0)
    .slice(-100)
    .sort((left, right) => left.at_ms - right.at_ms),
)

function speakerName(text: SessionText): string {
  if (text.speaker_type === 'teacher') {
    return '教师'
  }
  if (text.speaker_type === 'student') {
    const student = text.speaker_ref ? props.students[text.speaker_ref] : null
    return student?.name ?? '学生'
  }
  if (text.speaker_type === 'examiner') {
    return '考官'
  }
  if (text.speaker_type === 'candidate') {
    return '考生'
  }
  return text.speaker_type || '对话'
}

function kindLabel(text: SessionText): string {
  switch (text.kind) {
    case 'question':
      return '提问'
    case 'answer':
      return '回答'
    case 'follow_up':
      return '追问'
    default:
      return ''
  }
}

function formatTime(atMs: number): string {
  const total = Math.max(0, Math.floor(atMs / 1000))
  const minutes = Math.floor(total / 60)
  const seconds = total % 60
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
}

async function load(): Promise<void> {
  if (!props.sessionId) {
    return
  }
  const generation = ++loadGeneration
  loading.value = texts.value.length === 0
  error.value = ''
  try {
    const result = await getTexts(props.sessionId)
    // A slow initial request must not overwrite a newer refresh with old rows.
    if (generation !== loadGeneration) {
      return
    }
    texts.value = result
    await nextTick()
    if (scroller.value) {
      scroller.value.scrollTop = scroller.value.scrollHeight
    }
  } catch {
    if (generation === loadGeneration) {
      error.value = '对话记录加载失败'
    }
  } finally {
    if (generation === loadGeneration) {
      loading.value = false
    }
  }
}

watch(
  () => props.refreshToken,
  () => {
    void load()
  },
)

watch(
  () => props.liveText,
  async () => {
    await nextTick()
    if (scroller.value) {
      scroller.value.scrollTop = scroller.value.scrollHeight
    }
  },
)

let pollTimer: number | null = null

onMounted(() => {
  if (!props.active) {
    return
  }
  void load()
  pollTimer = window.setInterval(() => {
    void load()
  }, 2_000)
})

onBeforeUnmount(() => {
  if (pollTimer !== null) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
})
</script>

<template>
  <section class="dialogue-panel" data-testid="dialogue-panel" aria-label="课堂对话">
    <header class="dialogue-panel__header">
      <h2 class="dialogue-panel__title">课堂对话</h2>
      <ElTag v-if="liveText" type="warning" size="small">识别中</ElTag>
    </header>

    <ElAlert v-if="error" class="dialogue-panel__state" type="warning" :closable="false">
      <template #title>{{ error }}</template>
    </ElAlert>
    <p v-else-if="loading" class="dialogue-panel__state">加载对话…</p>
    <ElEmpty
      v-else-if="visibleTexts.length === 0 && !liveText"
      class="dialogue-panel__state"
      description="提问后，教师和学生的对话会显示在这里"
      :image-size="40"
    />

    <div ref="scroller" class="dialogue-panel__scroll">
      <article
        v-for="text in visibleTexts"
        :key="text.id"
        class="dialogue-panel__row"
        :class="`dialogue-panel__row--${text.speaker_type}`"
      >
        <div class="dialogue-panel__meta">
          <span class="dialogue-panel__speaker">{{ speakerName(text) }}</span>
          <ElTag v-if="kindLabel(text)" size="small" type="info">{{ kindLabel(text) }}</ElTag>
          <span class="dialogue-panel__time">{{ formatTime(text.at_ms) }}</span>
        </div>
        <p class="dialogue-panel__text">{{ text.text }}</p>
      </article>

      <article v-if="liveText" class="dialogue-panel__row dialogue-panel__row--teacher">
        <div class="dialogue-panel__meta">
          <span class="dialogue-panel__speaker">教师</span>
          <ElTag size="small" type="warning">识别中</ElTag>
        </div>
        <p class="dialogue-panel__text dialogue-panel__text--live">{{ liveText }}</p>
      </article>
    </div>
  </section>
</template>

<style scoped>
.dialogue-panel {
  display: flex;
  flex-direction: column;
  min-height: 200px;
  max-height: 360px;
  border: 1px solid rgba(148, 163, 184, 0.25);
  border-radius: 12px;
  background: rgba(15, 23, 42, 0.55);
}

.dialogue-panel__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  border-bottom: 1px solid rgba(148, 163, 184, 0.18);
}

.dialogue-panel__title {
  margin: 0;
  font-size: 14px;
}

.dialogue-panel__state {
  margin: 12px;
}

.dialogue-panel__scroll {
  flex: 1;
  overflow-y: auto;
  padding: 10px 12px;
}

.dialogue-panel__row {
  margin-bottom: 12px;
  padding-left: 10px;
  border-left: 2px solid rgba(148, 163, 184, 0.35);
}

.dialogue-panel__row--student {
  border-left-color: #60a5fa;
}

.dialogue-panel__row--teacher {
  border-left-color: #fbbf24;
}

.dialogue-panel__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.dialogue-panel__speaker {
  font-size: 12px;
  color: #cbd5e1;
}

.dialogue-panel__time {
  margin-left: auto;
  font-size: 11px;
  color: #94a3b8;
}

.dialogue-panel__text {
  margin: 0;
  color: #e5e7eb;
  font-size: 13px;
  line-height: 1.5;
  white-space: pre-wrap;
}

.dialogue-panel__text--live {
  color: #fbbf24;
}
</style>
