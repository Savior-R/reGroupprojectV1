<script setup lang="ts">
import type { InterviewQuestionSnapshot } from '../../types/session'

/**
 * Structured-interview question panel (05C §7, 09 §6.5).
 *
 * Renders only the student-visible question fields copied into the snapshot /
 * `interview.question_shown` event: text, order and total. Examiner text is
 * persisted server-side; no answer points, rubric or reference material is
 * ever passed to this component.
 */

const props = defineProps<{
  question: InterviewQuestionSnapshot | null
}>()
</script>

<template>
  <section class="question-panel" data-testid="question-panel" aria-live="polite">
    <template v-if="props.question">
      <p class="question-panel__meta" data-testid="question-order">
        第 {{ props.question.order }} / {{ props.question.total }} 题
      </p>
      <p class="question-panel__text" data-testid="question-text">{{ props.question.text }}</p>
    </template>
    <p v-else class="question-panel__empty" data-testid="question-empty">等待考官提问…</p>
  </section>
</template>

<style scoped>
.question-panel {
  display: flex;
  min-height: 0;
  flex: 1;
  flex-direction: column;
  gap: 14px;
  padding: 28px 32px;
  border: 1px solid rgba(148, 163, 184, 0.2);
  border-radius: 16px;
  background: rgba(15, 23, 42, 0.86);
}

.question-panel__meta {
  margin: 0;
  color: #94a3b8;
  font-size: 13px;
  letter-spacing: 0.04em;
}

.question-panel__text {
  margin: 0;
  color: #f8fafc;
  font-size: 22px;
  line-height: 1.6;
  white-space: pre-wrap;
}

.question-panel__empty {
  margin: auto;
  color: #94a3b8;
  font-size: 15px;
}
</style>