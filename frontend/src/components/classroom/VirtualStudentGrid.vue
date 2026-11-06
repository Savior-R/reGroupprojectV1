<script setup lang="ts">
import VirtualStudentCard from './VirtualStudentCard.vue'
import type { ClassroomStudentState } from '../../utils/classroomReducer'

/**
 * Virtual-student sidebar (09 §6.5). Cards render only the public projection;
 * selecting a student is page state, so the grid just forwards the event.
 */

defineProps<{
  students: ClassroomStudentState[]
  selectedId?: string | null
  disabled?: boolean
}>()

const emit = defineEmits<{
  select: [studentId: string]
}>()
</script>

<template>
  <section class="virtual-student-grid" aria-label="虚拟学生">
    <header class="virtual-student-grid__header">
      <h2 class="virtual-student-grid__title">虚拟学生</h2>
      <span class="virtual-student-grid__count">{{ students.length }} 人</span>
    </header>

    <p v-if="students.length === 0" class="virtual-student-grid__empty">
      暂无虚拟学生
    </p>

    <div v-else class="virtual-student-grid__list">
      <VirtualStudentCard
        v-for="student in students"
        :key="student.virtual_student_id"
        :student="student"
        :selected="student.virtual_student_id === selectedId"
        :disabled="disabled"
        @select="emit('select', $event)"
      />
    </div>
  </section>
</template>

<style scoped>
.virtual-student-grid {
  display: flex;
  min-height: 0;
  flex-direction: column;
  gap: 10px;
  padding: 12px;
  border: 1px solid rgba(148, 163, 184, 0.18);
  border-radius: 12px;
  background: rgba(15, 23, 42, 0.7);
}

.virtual-student-grid__header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}

.virtual-student-grid__title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
}

.virtual-student-grid__count {
  color: #94a3b8;
  font-size: 12px;
}

.virtual-student-grid__list {
  display: flex;
  min-height: 0;
  flex-direction: column;
  gap: 6px;
  overflow-y: auto;
}

.virtual-student-grid__empty {
  margin: 0;
  color: #94a3b8;
  font-size: 13px;
}
</style>