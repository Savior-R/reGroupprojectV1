<script setup lang="ts">
import { computed, ref } from 'vue'
import type { ReplayEvent } from '../../api/replay'

/**
 * Replay event timeline (05B §7.3, 09 §10.4).
 *
 * Events are grouped by the four review filters (teacher / student /
 * discipline / interview). Clicking a row emits its session-relative `at_ms`;
 * the player is responsible for applying per-stream offsets.
 */

const props = defineProps<{
  events: ReplayEvent[]
}>()

const emit = defineEmits<{
  seek: [atMs: number]
}>()

type EventFilter = 'all' | 'teacher' | 'student' | 'discipline' | 'interview'
type EventCategory = Exclude<EventFilter, 'all'>

const FILTERS: ReadonlyArray<{ key: EventFilter; label: string }> = [
  { key: 'all', label: '全部' },
  { key: 'teacher', label: '教师' },
  { key: 'student', label: '学生' },
  { key: 'discipline', label: '纪律' },
  { key: 'interview', label: '面试' },
]

const TYPE_LABELS: Record<string, string> = {
  'teacher.called_on': '点名',
  'teacher.marked': '评价回答',
  'teacher.management_action': '课堂管理',
  'classroom.question_asked': '提问',
  'classroom.qa_ended': '问答结束',
  'student.hand_raised': '举手',
  'student.hand_lowered': '放下手',
  'student.question': '学生提问',
  'student.answer': '学生回答',
  'student.follow_up': '追问',
  'discipline.event': '纪律事件',
  'classroom.discipline_level_changed': '纪律等级变化',
  'interview.question_shown': '展示题目',
  'interview.answer_started': '开始回答',
  'interview.answer_ended': '结束回答',
  'interview.next_question': '下一题',
  'interview.ended': '面试结束',
  'session.started': '会话开始',
  'session.ended': '会话结束',
  'session.failed': '会话失败',
  'submission.created': '提交',
}

const activeFilter = ref<EventFilter>('all')

function categoryOf(type: string): EventCategory | null {
  if (type.startsWith('teacher.')) {
    return 'teacher'
  }
  if (type.startsWith('student.')) {
    return 'student'
  }
  if (type.startsWith('discipline.')) {
    return 'discipline'
  }
  if (type.startsWith('interview.')) {
    return 'interview'
  }
  // Classroom management events belong to the teacher's activity.
  if (type === 'classroom.discipline_level_changed' || type === 'classroom.hand_window_timeout') {
    return 'discipline'
  }
  if (type.startsWith('classroom.') || type.startsWith('dialogue.')) {
    return 'teacher'
  }
  return null
}

const visibleEvents = computed(() =>
  props.events.filter(
    (event) => activeFilter.value === 'all' || categoryOf(event.type) === activeFilter.value,
  ),
)

function typeLabel(type: string): string {
  return TYPE_LABELS[type] ?? type
}

function formatTime(atMs: number): string {
  const totalSeconds = Math.max(0, Math.floor(atMs / 1000))
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
}
</script>

<template>
  <section class="event-timeline" aria-label="事件时间线" data-testid="event-timeline">
    <header class="event-timeline__header">
      <h2 class="event-timeline__title">事件流</h2>
      <div class="event-timeline__filters" role="group" aria-label="事件筛选">
        <button
          v-for="filter in FILTERS"
          :key="filter.key"
          type="button"
          class="event-timeline__filter"
          :class="{ 'event-timeline__filter--active': activeFilter === filter.key }"
          :data-testid="`event-filter-${filter.key}`"
          :aria-pressed="activeFilter === filter.key"
          @click="activeFilter = filter.key"
        >
          {{ filter.label }}
        </button>
      </div>
    </header>

    <p v-if="visibleEvents.length === 0" class="event-timeline__empty" data-testid="event-empty">
      暂无事件
    </p>
    <ol v-else class="event-timeline__list">
      <li v-for="event in visibleEvents" :key="event.event_id">
        <button
          type="button"
          class="event-timeline__item"
          :data-testid="`event-item-${event.event_id}`"
          :title="`跳转到 ${formatTime(event.at_ms)}`"
          @click="emit('seek', event.at_ms)"
        >
          <span class="event-timeline__time">{{ formatTime(event.at_ms) }}</span>
          <span class="event-timeline__type">{{ typeLabel(event.type) }}</span>
        </button>
      </li>
    </ol>
  </section>
</template>

<style scoped>
.event-timeline {
  display: flex;
  min-height: 0;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
  border: 1px solid rgba(148, 163, 184, 0.22);
  border-radius: 12px;
  background: rgba(15, 23, 42, 0.78);
}

.event-timeline__header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.event-timeline__title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
}

.event-timeline__filters {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.event-timeline__filter {
  padding: 3px 8px;
  border: 1px solid rgba(148, 163, 184, 0.28);
  border-radius: 999px;
  background: transparent;
  color: #cbd5e1;
  font-size: 12px;
  cursor: pointer;
}

.event-timeline__filter--active {
  border-color: rgba(96, 165, 250, 0.8);
  background: rgba(59, 130, 246, 0.22);
  color: #e5e7eb;
}

.event-timeline__list {
  display: flex;
  min-height: 0;
  flex-direction: column;
  gap: 4px;
  margin: 0;
  padding: 0;
  overflow-y: auto;
  list-style: none;
}

.event-timeline__item {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 10px;
  padding: 5px 8px;
  border: 1px solid transparent;
  border-radius: 8px;
  background: rgba(148, 163, 184, 0.08);
  color: #e5e7eb;
  font-size: 12px;
  text-align: left;
  cursor: pointer;
}

.event-timeline__item:hover {
  border-color: rgba(96, 165, 250, 0.55);
  background: rgba(59, 130, 246, 0.16);
}

.event-timeline__time {
  flex: none;
  color: #93c5fd;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

.event-timeline__type {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.event-timeline__empty {
  margin: 8px 0;
  color: #94a3b8;
  font-size: 12px;
}
</style>