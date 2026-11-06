<script setup lang="ts">
import { computed } from 'vue'
import type { SessionStatus } from '../../types/session'

/**
 * Classroom command bar (05A §6, 09 §6.5).
 *
 * The component owns no business state: it receives the reducer-derived status
 * and emits commands. Every control is disabled unless the session is
 * `in_progress`; dialogue controls additionally require an active dialogue.
 */

type Correctness = 'correct' | 'partial' | 'wrong'

const props = withDefaults(
  defineProps<{
    status: SessionStatus | null
    dialogueActive?: boolean
    answerId?: string | null
    selectedStudentId?: string | null
    failed?: boolean
    ending?: boolean
  }>(),
  {
    dialogueActive: false,
    answerId: null,
    selectedStudentId: null,
    failed: false,
    ending: false,
  },
)

const emit = defineEmits<{
  askClass: []
  askStudentQuestion: []
  callOn: []
  mark: [correctness: Correctness]
  remind: []
  endDialogue: []
  endQa: []
  endSession: []
}>()

const baseDisabled = computed(
  () => props.failed || props.ending || props.status !== 'in_progress',
)
const studentDisabled = computed(() => baseDisabled.value || !props.selectedStudentId)
const markDisabled = computed(() => baseDisabled.value || !props.answerId)
const dialogueDisabled = computed(() => baseDisabled.value || !props.dialogueActive)
</script>

<template>
  <div class="classroom-controls" role="toolbar" aria-label="课堂控制">
    <div class="classroom-controls__group">
      <span class="classroom-controls__label">提问</span>
      <button
        type="button"
        class="classroom-controls__button"
        data-testid="ask-class"
        :disabled="baseDisabled"
        @click="emit('askClass')"
      >
        向全班提问
      </button>
      <button
        type="button"
        class="classroom-controls__button"
        data-testid="ask-student-question"
        :disabled="baseDisabled"
        @click="emit('askStudentQuestion')"
      >
        让学生提问
      </button>
    </div>

    <div class="classroom-controls__group">
      <span class="classroom-controls__label">学生</span>
      <button
        type="button"
        class="classroom-controls__button"
        data-testid="call-on"
        :disabled="studentDisabled"
        @click="emit('callOn')"
      >
        点名
      </button>
      <button
        type="button"
        class="classroom-controls__button"
        data-testid="remind"
        :disabled="studentDisabled"
        @click="emit('remind')"
      >
        提醒
      </button>
    </div>

    <div class="classroom-controls__group">
      <span class="classroom-controls__label">评价</span>
      <button
        type="button"
        class="classroom-controls__button"
        data-testid="mark-correct"
        :disabled="markDisabled"
        @click="emit('mark', 'correct')"
      >
        对
      </button>
      <button
        type="button"
        class="classroom-controls__button"
        data-testid="mark-partial"
        :disabled="markDisabled"
        @click="emit('mark', 'partial')"
      >
        不完整
      </button>
      <button
        type="button"
        class="classroom-controls__button"
        data-testid="mark-wrong"
        :disabled="markDisabled"
        @click="emit('mark', 'wrong')"
      >
        错
      </button>
    </div>

    <div class="classroom-controls__group">
      <span class="classroom-controls__label">问答</span>
      <button
        type="button"
        class="classroom-controls__button"
        data-testid="end-dialogue"
        :disabled="dialogueDisabled"
        @click="emit('endDialogue')"
      >
        结束对话
      </button>
      <button
        type="button"
        class="classroom-controls__button"
        data-testid="end-qa"
        :disabled="baseDisabled"
        @click="emit('endQa')"
      >
        结束问答
      </button>
    </div>

    <div class="classroom-controls__group classroom-controls__group--end">
      <button
        type="button"
        class="classroom-controls__button classroom-controls__button--danger"
        data-testid="end-session"
        :disabled="baseDisabled"
        @click="emit('endSession')"
      >
        {{ ending ? '结束中…' : '结束试讲' }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.classroom-controls {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px 20px;
  padding: 10px 14px;
  border: 1px solid rgba(148, 163, 184, 0.2);
  border-radius: 12px;
  background: rgba(15, 23, 42, 0.86);
}

.classroom-controls__group {
  display: flex;
  align-items: center;
  gap: 6px;
}

.classroom-controls__group--end {
  margin-left: auto;
}

.classroom-controls__label {
  color: #94a3b8;
  font-size: 12px;
}

.classroom-controls__button {
  padding: 6px 12px;
  border: 1px solid rgba(148, 163, 184, 0.3);
  border-radius: 8px;
  background: rgba(148, 163, 184, 0.12);
  color: #e5e7eb;
  font-size: 13px;
  cursor: pointer;
  transition:
    background 0.15s ease,
    border-color 0.15s ease;
}

.classroom-controls__button:hover:not(:disabled) {
  border-color: rgba(96, 165, 250, 0.7);
  background: rgba(59, 130, 246, 0.18);
}

.classroom-controls__button:disabled {
  cursor: default;
  opacity: 0.45;
}

.classroom-controls__button--danger {
  border-color: rgba(248, 113, 113, 0.55);
  background: rgba(185, 28, 28, 0.24);
}

.classroom-controls__button--danger:hover:not(:disabled) {
  border-color: rgba(248, 113, 113, 0.9);
  background: rgba(185, 28, 28, 0.38);
}
</style>