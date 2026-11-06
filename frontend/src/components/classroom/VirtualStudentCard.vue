<script setup lang="ts">
import { computed } from 'vue'
import type { VirtualStudentState } from '../../types/session'

/**
 * Public virtual-student card (09 §6.5, 09 §7).
 *
 * The card intentionally declares only the public projection: name, avatar and
 * runtime state. Personality, parameters, attention/energy values and any
 * future privileged field must never be declared or rendered here.
 */

const props = withDefaults(
  defineProps<{
    student: {
      virtual_student_id: string
      name: string
      avatar?: string | null
      state: VirtualStudentState
    }
    selected?: boolean
    disabled?: boolean
  }>(),
  {
    selected: false,
    disabled: false,
  },
)

const emit = defineEmits<{
  select: [studentId: string]
}>()

const STATE_LABELS: Record<VirtualStudentState, string> = {
  focused: '专注',
  distracted: '走神',
  whispering: '说话',
  hand_raised: '举手',
  called_on: '被点名',
  answering: '回答中',
  dialogue: '对话中',
}

const stateLabel = computed(() => STATE_LABELS[props.student.state] ?? props.student.state)
const initial = computed(() => props.student.name.trim().charAt(0) || '?')

function select(): void {
  if (props.disabled) {
    return
  }
  emit('select', props.student.virtual_student_id)
}
</script>

<template>
  <button
    type="button"
    class="virtual-student-card"
    :class="{
      'virtual-student-card--selected': selected,
      'virtual-student-card--raised': student.state === 'hand_raised',
    }"
    :aria-pressed="selected"
    :disabled="disabled"
    :data-testid="`virtual-student-${student.virtual_student_id}`"
    @click="select"
  >
    <span class="virtual-student-card__avatar" aria-hidden="true">
      <img
        v-if="student.avatar"
        class="virtual-student-card__avatar-image"
        :src="student.avatar"
        :alt="student.name"
        loading="lazy"
      />
      <span v-else class="virtual-student-card__avatar-initial">{{ initial }}</span>
    </span>
    <span class="virtual-student-card__body">
      <span class="virtual-student-card__name">{{ student.name }}</span>
      <span class="virtual-student-card__state">{{ stateLabel }}</span>
    </span>
  </button>
</template>

<style scoped>
.virtual-student-card {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 8px 10px;
  border: 1px solid rgba(148, 163, 184, 0.2);
  border-radius: 10px;
  background: rgba(148, 163, 184, 0.08);
  color: inherit;
  text-align: left;
  cursor: pointer;
  transition:
    border-color 0.15s ease,
    background 0.15s ease;
}

.virtual-student-card:hover:not(:disabled) {
  background: rgba(148, 163, 184, 0.16);
}

.virtual-student-card:disabled {
  cursor: default;
  opacity: 0.6;
}

.virtual-student-card--selected {
  border-color: rgba(96, 165, 250, 0.8);
  background: rgba(59, 130, 246, 0.18);
}

.virtual-student-card--raised {
  border-color: rgba(250, 204, 21, 0.55);
}

.virtual-student-card__avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  flex: none;
  border-radius: 50%;
  background: rgba(148, 163, 184, 0.2);
  overflow: hidden;
  font-size: 14px;
  font-weight: 600;
}

.virtual-student-card__avatar-image {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.virtual-student-card__avatar-initial {
  color: #cbd5e1;
}

.virtual-student-card__body {
  display: flex;
  min-width: 0;
  flex-direction: column;
}

.virtual-student-card__name {
  overflow: hidden;
  font-size: 14px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.virtual-student-card__state {
  color: #94a3b8;
  font-size: 12px;
}
</style>