import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type {
  InterviewQuestionSnapshot,
  RecordingSnapshot,
  SessionSnapshot,
  SessionStatus,
} from '../types/session'

/**
 * Interview room state (05C §7).
 *
 * The store is deliberately a thin Pinia container: the pure WebSocket /
 * classroom logic lives in `utils/wsClient.ts` and `utils/classroomReducer.ts`.
 * Pages hydrate it from a snapshot or from reducer output through the explicit
 * setters below.
 */

export type InterviewRecordingState = 'active' | 'lost'

export const useInterviewStore = defineStore('interview', () => {
  const session = ref<SessionSnapshot | null>(null)
  const currentQuestion = ref<InterviewQuestionSnapshot | null>(null)
  const answerId = ref<string | null>(null)
  const remainingMs = ref<number | null>(null)
  const ended = ref(false)
  const recording = ref<RecordingSnapshot | null>(null)
  const recordingState = ref<InterviewRecordingState>('active')

  const status = computed<SessionStatus | null>(() => session.value?.state ?? null)
  const mode = computed(() => session.value?.mode ?? null)
  const questionOrder = computed(() => currentQuestion.value?.order ?? null)
  const questionTotal = computed(() => currentQuestion.value?.total ?? null)
  const remainingSeconds = computed(() =>
    remainingMs.value === null ? null : Math.max(0, Math.ceil(remainingMs.value / 1000)),
  )
  const isRecordingLost = computed(() => recordingState.value === 'lost')

  /** Seeds every field from the connection snapshot. */
  function applySnapshot(snapshot: SessionSnapshot): void {
    session.value = snapshot
    currentQuestion.value = snapshot.interview?.current ?? null
    answerId.value = snapshot.interview?.answer_id ?? null
    remainingMs.value = snapshot.interview?.remaining_ms ?? null
    ended.value = snapshot.interview?.ended ?? false
    recording.value = snapshot.recording ?? null
  }

  function setQuestion(question: InterviewQuestionSnapshot | null): void {
    currentQuestion.value = question
  }

  function setAnswerId(next: string | null): void {
    answerId.value = next
  }

  function setRemainingMs(next: number | null): void {
    remainingMs.value = next
  }

  function markEnded(): void {
    ended.value = true
    answerId.value = null
    remainingMs.value = 0
  }

  function setRecording(next: RecordingSnapshot | null): void {
    recording.value = next
  }

  function setRecordingState(next: InterviewRecordingState): void {
    recordingState.value = next
  }

  function reset(): void {
    session.value = null
    currentQuestion.value = null
    answerId.value = null
    remainingMs.value = null
    ended.value = false
    recording.value = null
    recordingState.value = 'active'
  }

  return {
    session,
    currentQuestion,
    answerId,
    remainingMs,
    ended,
    recording,
    recordingState,
    status,
    mode,
    questionOrder,
    questionTotal,
    remainingSeconds,
    isRecordingLost,
    applySnapshot,
    setQuestion,
    setAnswerId,
    setRemainingMs,
    markEnded,
    setRecording,
    setRecordingState,
    reset,
  }
})