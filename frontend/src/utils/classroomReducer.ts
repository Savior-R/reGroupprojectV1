import type { EventPayload } from '../types/event'
import type { StreamType } from '../types/recording'
import type {
  InterviewQuestionSnapshot,
  PublicSessionConfig,
  RecordingSnapshot,
  ServerEvent,
  SessionMode,
  SessionStatus,
  VirtualStudentSnapshot,
  VirtualStudentState,
} from '../types/session'

/**
 * Pure classroom state reducer (05A §6, 05C §7).
 *
 * The reducer consumes the same `ServerEvent` envelopes the control channel
 * emits and returns a new snapshot. Unknown events return the previous state
 * unchanged. Only public fields are copied out of `session.snapshot`: the
 * backend already filters privileged data, but the whitelist here guarantees
 * that a future payload leak cannot reach the UI.
 */

export interface ClassroomStudentState extends VirtualStudentSnapshot {
  /** Open raise id while the hand is up; cleared on lower/called-on. */
  hand_raise_id: string | null
}

export interface ClassroomPhaseState {
  macroState: string | null
  qaPhase: string | null
  questionIntent: string | null
  questionSource: string | null
  questionSuggestedConfidence: number | null
}

export interface ClassroomInterviewState {
  current: InterviewQuestionSnapshot | null
  answerId: string | null
  answering: boolean
  remainingMs: number | null
  ended: boolean
}

export interface ClassroomDialogueState {
  dialogueId: string
  studentId: string | null
}

export interface ClassroomStudentActivity {
  type: 'student.question' | 'student.answer' | 'student.follow_up'
  studentId: string | null
  textId: string | null
  questionId: string | null
  answerId: string | null
  quality: string | null
}

export interface ClassroomManagementAction {
  studentId: string | null
  action: string | null
}

export interface ClassroomDisciplineEvent {
  level: number
  studentIds: string[]
  eventType: string | null
}

export interface ClassroomError {
  code: string
  message: string
  commandId: string | null
}

export interface ClassroomState {
  sessionId: string | null
  status: SessionStatus | null
  mode: SessionMode | null
  publicConfig: PublicSessionConfig
  elapsedMs: number
  remainingMs: number | null
  disciplineLevel: number
  students: Record<string, ClassroomStudentState>
  /** hand_raise_id -> student_id for every open hand. */
  handRaises: Record<string, string>
  activeStudentId: string | null
  classroom: ClassroomPhaseState
  dialogue: ClassroomDialogueState | null
  interview: ClassroomInterviewState
  recording: RecordingSnapshot | null
  /** answer_id -> correctness. */
  marks: Record<string, string>
  lastStudentActivity: ClassroomStudentActivity | null
  lastManagementAction: ClassroomManagementAction | null
  lastDisciplineEvent: ClassroomDisciplineEvent | null
  lastError: ClassroomError | null
  endedReason: string | null
  failureReason: string | null
  failureCode: string | null
  submissionId: string | null
  lastSeq: number
}

const SESSION_STATUSES: readonly SessionStatus[] = [
  'preparing',
  'in_progress',
  'ended',
  'reviewing',
  'submitted',
  'cancelled',
  'failed',
]

const SESSION_MODES: readonly SessionMode[] = ['free', 'scenario', 'interview']

const STUDENT_STATES: readonly VirtualStudentState[] = [
  'focused',
  'distracted',
  'whispering',
  'hand_raised',
  'called_on',
  'answering',
  'dialogue',
]

const STREAM_TYPES: readonly StreamType[] = ['camera', 'screen', 'audio']

const ACTIVE_STUDENT_STATES: ReadonlySet<VirtualStudentState> = new Set([
  'called_on',
  'answering',
  'dialogue',
])

export function createInitialClassroomState(): ClassroomState {
  return {
    sessionId: null,
    status: null,
    mode: null,
    publicConfig: {},
    elapsedMs: 0,
    remainingMs: null,
    disciplineLevel: 0,
    students: {},
    handRaises: {},
    activeStudentId: null,
    classroom: {
      macroState: null,
      qaPhase: null,
      questionIntent: null,
      questionSource: null,
      questionSuggestedConfidence: null,
    },
    dialogue: null,
    interview: {
      current: null,
      answerId: null,
      answering: false,
      remainingMs: null,
      ended: false,
    },
    recording: null,
    marks: {},
    lastStudentActivity: null,
    lastManagementAction: null,
    lastDisciplineEvent: null,
    lastError: null,
    endedReason: null,
    failureReason: null,
    failureCode: null,
    submissionId: null,
    lastSeq: 0,
  }
}

// ---------------------------------------------------------------------------
// Payload helpers
// ---------------------------------------------------------------------------

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function stringValue(value: unknown, fallback = ''): string {
  return typeof value === 'string' ? value : fallback
}

function nullableString(value: unknown): string | null {
  return typeof value === 'string' ? value : null
}

function numberValue(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback
}

function optionalNumber(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined
}

function oneOf<T extends string>(value: unknown, allowed: readonly T[]): T | undefined {
  return typeof value === 'string' && (allowed as readonly string[]).includes(value)
    ? (value as T)
    : undefined
}

function isStreamType(value: string): value is StreamType {
  return (STREAM_TYPES as readonly string[]).includes(value)
}

function stringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : []
}

// ---------------------------------------------------------------------------
// Snapshot projection (public fields only)
// ---------------------------------------------------------------------------

function snapshotStudents(raw: unknown): Record<string, ClassroomStudentState> {
  const students: Record<string, ClassroomStudentState> = {}
  if (!Array.isArray(raw)) {
    return students
  }
  for (const item of raw) {
    if (!isRecord(item)) {
      continue
    }
    const id = nullableString(item.virtual_student_id)
    if (!id) {
      continue
    }
    students[id] = {
      virtual_student_id: id,
      name: stringValue(item.name),
      avatar: nullableString(item.avatar),
      ...(optionalNumber(item.seat_no) === undefined ? {} : { seat_no: optionalNumber(item.seat_no) }),
      ...(optionalNumber(item.sort_order) === undefined
        ? {}
        : { sort_order: optionalNumber(item.sort_order) }),
      state: oneOf(item.state, STUDENT_STATES) ?? 'focused',
      attention: numberValue(item.attention, 0),
      hand_raise_energy: numberValue(item.hand_raise_energy, 0),
      hand_raise_id: null,
    }
  }
  return students
}

function snapshotQuestion(raw: unknown): InterviewQuestionSnapshot | null {
  if (!isRecord(raw)) {
    return null
  }
  const questionId = nullableString(raw.question_id)
  if (!questionId) {
    return null
  }
  return {
    question_id: questionId,
    order: numberValue(raw.order, 0),
    total: numberValue(raw.total, 0),
    text: stringValue(raw.text),
  }
}

function snapshotInterview(raw: unknown): ClassroomInterviewState {
  if (!isRecord(raw)) {
    return {
      current: null,
      answerId: null,
      answering: false,
      remainingMs: null,
      ended: false,
    }
  }
  const answerId = nullableString(raw.answer_id)
  const ended = raw.ended === true
  return {
    current: snapshotQuestion(raw.current),
    answerId,
    answering: answerId !== null && !ended,
    remainingMs: optionalNumber(raw.remaining_ms) ?? null,
    ended,
  }
}

function snapshotRecording(raw: unknown): RecordingSnapshot | null {
  if (!isRecord(raw)) {
    return null
  }
  const streams: Partial<Record<StreamType, string>> = {}
  if (isRecord(raw.streams)) {
    for (const [key, value] of Object.entries(raw.streams)) {
      if (isStreamType(key) && typeof value === 'string') {
        streams[key] = value
      }
    }
  }
  return {
    required_streams: stringArray(raw.required_streams).filter(isStreamType),
    streams,
    ready: raw.ready === true,
  }
}

function classroomRemaining(
  publicConfig: PublicSessionConfig,
  mode: SessionMode | null,
  elapsedMs: number,
): number | null {
  if (mode === 'interview') {
    return null
  }
  const maxSeconds = optionalNumber(publicConfig.max_seconds)
  return maxSeconds === undefined ? null : Math.max(0, Math.round(maxSeconds * 1000 - elapsedMs))
}

function applySnapshot(state: ClassroomState, event: ServerEvent): ClassroomState {
  const payload = event.payload
  const publicConfig: PublicSessionConfig = isRecord(payload.public_config)
    ? { ...payload.public_config }
    : {}
  const mode = oneOf(payload.mode, SESSION_MODES) ?? state.mode
  const interview = snapshotInterview(payload.interview)
  const elapsedMs = numberValue(payload.elapsed_ms, 0)
  const classroomRaw = isRecord(payload.classroom) ? payload.classroom : null

  return {
    ...state,
    sessionId: nullableString(event.session_id) ?? state.sessionId,
    status: oneOf(payload.state, SESSION_STATUSES) ?? state.status,
    mode,
    publicConfig,
    elapsedMs,
    remainingMs: interview.remainingMs ?? classroomRemaining(publicConfig, mode, elapsedMs),
    disciplineLevel: 0,
    students: snapshotStudents(payload.virtual_students),
    handRaises: {},
    activeStudentId: null,
    classroom: {
      macroState: classroomRaw ? nullableString(classroomRaw.state) : null,
      qaPhase: classroomRaw ? nullableString(classroomRaw.qa_phase) : null,
      questionIntent: null,
      questionSource: null,
      questionSuggestedConfidence: null,
    },
    dialogue: null,
    interview,
    recording: snapshotRecording(payload.recording),
    marks: {},
    lastStudentActivity: null,
    lastManagementAction: null,
    lastDisciplineEvent: null,
    lastError: null,
    endedReason: null,
    failureReason: null,
    failureCode: null,
    submissionId: null,
    lastSeq: optionalNumber(payload.last_seq) ?? state.lastSeq,
  }
}

// ---------------------------------------------------------------------------
// Mutations (always return new maps/objects, never mutate the input)
// ---------------------------------------------------------------------------

function updateStudent(
  students: Record<string, ClassroomStudentState>,
  studentId: string,
  patch: Partial<ClassroomStudentState>,
): Record<string, ClassroomStudentState> {
  const existing = students[studentId]
  if (!existing) {
    return {
      ...students,
      [studentId]: {
        virtual_student_id: studentId,
        name: '',
        avatar: null,
        state: 'focused',
        attention: 0,
        hand_raise_energy: 0,
        hand_raise_id: null,
        ...patch,
      },
    }
  }
  return {
    ...students,
    [studentId]: { ...existing, ...patch },
  }
}

function removeHandRaise(
  handRaises: Record<string, string>,
  handRaiseId: string | null,
  studentId?: string,
): Record<string, string> {
  const next = { ...handRaises }
  if (handRaiseId !== null) {
    delete next[handRaiseId]
  } else if (studentId !== undefined) {
    for (const [id, owner] of Object.entries(next)) {
      if (owner === studentId) {
        delete next[id]
      }
    }
  }
  return next
}

function clearHandRaise(
  state: ClassroomState,
  studentId: string,
  handRaiseId: string | null,
): ClassroomState {
  const student = state.students[studentId]
  const patch: Partial<ClassroomStudentState> = { hand_raise_id: null }
  if (student?.state === 'hand_raised') {
    patch.state = 'focused'
  }
  return {
    ...state,
    students: updateStudent(state.students, studentId, patch),
    handRaises: removeHandRaise(state.handRaises, handRaiseId, studentId),
  }
}

// ---------------------------------------------------------------------------
// Event handlers
// ---------------------------------------------------------------------------

function applyClassroomStateChanged(state: ClassroomState, payload: EventPayload): ClassroomState {
  const to = nullableString(payload.to)
  const phase =
    payload.qa_phase === null
      ? null
      : (nullableString(payload.qa_phase) ?? state.classroom.qaPhase)
  return {
    ...state,
    classroom: {
      ...state.classroom,
      macroState: to ?? state.classroom.macroState,
      qaPhase: phase,
      questionIntent: phase === null ? null : state.classroom.questionIntent,
      questionSource: phase === null ? null : state.classroom.questionSource,
    },
    ...(to === 'ended' ? { activeStudentId: null } : {}),
  }
}

function applyStudentStateChanged(state: ClassroomState, payload: EventPayload): ClassroomState {
  const studentId = nullableString(payload.student_id)
  if (!studentId) {
    return state
  }
  const to = oneOf(payload.to, STUDENT_STATES)
  const students = to ? updateStudent(state.students, studentId, { state: to }) : state.students
  let activeStudentId = state.activeStudentId
  if (to === 'called_on') {
    activeStudentId = studentId
  } else if (activeStudentId === studentId && to && !ACTIVE_STUDENT_STATES.has(to)) {
    activeStudentId = null
  }
  return { ...state, students, activeStudentId }
}

function applyStudentHandRaised(state: ClassroomState, payload: EventPayload): ClassroomState {
  const studentId = nullableString(payload.student_id)
  if (!studentId) {
    return state
  }
  const handRaiseId = nullableString(payload.hand_raise_id)
  return {
    ...state,
    students: updateStudent(state.students, studentId, {
      state: 'hand_raised',
      hand_raise_id: handRaiseId,
    }),
    handRaises:
      handRaiseId === null ? state.handRaises : { ...state.handRaises, [handRaiseId]: studentId },
  }
}

function applyStudentEvent(state: ClassroomState, event: ServerEvent): ClassroomState {
  const payload = event.payload
  const studentId = nullableString(payload.student_id)

  switch (event.type) {
    case 'student.state_changed':
      return applyStudentStateChanged(state, payload)

    case 'student.hand_raised':
      return applyStudentHandRaised(state, payload)

    case 'student.hand_lowered': {
      if (!studentId) {
        return state
      }
      return clearHandRaise(state, studentId, nullableString(payload.hand_raise_id))
    }

    case 'student.question':
    case 'student.answer':
    case 'student.follow_up': {
      if (!studentId) {
        return state
      }
      return {
        ...state,
        lastStudentActivity: {
          type: event.type,
          studentId,
          textId: nullableString(payload.text_id),
          questionId: nullableString(payload.question_id),
          answerId: nullableString(payload.answer_id),
          quality: nullableString(payload.quality),
        },
      }
    }

    default:
      return state
  }
}

function applyTeacherEvent(state: ClassroomState, event: ServerEvent): ClassroomState {
  const payload = event.payload
  const studentId = nullableString(payload.student_id)

  switch (event.type) {
    case 'teacher.called_on': {
      if (!studentId) {
        return state
      }
      const handRaiseId = nullableString(payload.hand_raise_id)
      return {
        ...state,
        students: updateStudent(state.students, studentId, {
          state: 'called_on',
          hand_raise_id: null,
        }),
        handRaises: removeHandRaise(state.handRaises, handRaiseId, studentId),
        activeStudentId: studentId,
      }
    }

    case 'teacher.marked': {
      const answerId = nullableString(payload.answer_id)
      const correctness = nullableString(payload.correctness)
      if (!answerId || !correctness) {
        return state
      }
      return { ...state, marks: { ...state.marks, [answerId]: correctness } }
    }

    case 'teacher.management_action':
      return {
        ...state,
        lastManagementAction: {
          studentId,
          action: nullableString(payload.action),
        },
      }

    default:
      return state
  }
}

function applyDialogueEvent(state: ClassroomState, event: ServerEvent): ClassroomState {
  const payload = event.payload
  const studentId = nullableString(payload.student_id)

  if (event.type === 'dialogue.started') {
    const dialogueId = nullableString(payload.dialogue_id)
    if (!dialogueId) {
      return state
    }
    return {
      ...state,
      dialogue: { dialogueId, studentId },
      students: studentId
        ? updateStudent(state.students, studentId, { state: 'dialogue' })
        : state.students,
      activeStudentId: studentId ?? state.activeStudentId,
    }
  }

  if (event.type === 'dialogue.ended') {
    const endedStudentId = studentId ?? state.dialogue?.studentId ?? null
    const student = endedStudentId ? state.students[endedStudentId] : undefined
    const students =
      endedStudentId && student?.state === 'dialogue'
        ? updateStudent(state.students, endedStudentId, { state: 'focused' })
        : state.students
    return {
      ...state,
      dialogue: null,
      students,
      activeStudentId:
        state.activeStudentId === endedStudentId ? null : state.activeStudentId,
    }
  }

  return state
}

function applyTimeTick(state: ClassroomState, payload: EventPayload): ClassroomState {
  const elapsedMs = numberValue(payload.elapsed_ms, state.elapsedMs)
  const remainingMs = optionalNumber(payload.remaining_ms) ?? state.remainingMs
  const disciplineLevel = numberValue(payload.discipline_level, state.disciplineLevel)

  let students = state.students
  if (Array.isArray(payload.states)) {
    for (const item of payload.states) {
      if (!isRecord(item)) {
        continue
      }
      const studentId = nullableString(item.virtual_student_id)
      if (!studentId) {
        continue
      }
      students = updateStudent(students, studentId, {})
      const current = students[studentId]
      if (!current) {
        continue
      }
      students = {
        ...students,
        [studentId]: {
          ...current,
          state: oneOf(item.state, STUDENT_STATES) ?? current.state,
          attention: numberValue(item.attention, current.attention),
          hand_raise_energy: numberValue(item.hand_raise_energy, current.hand_raise_energy),
        },
      }
    }
  }

  // Interview tick payloads carry the total remaining time at the top level
  // (05C §6.3); an explicit nested value wins when present.
  const nested = isRecord(payload.interview) ? optionalNumber(payload.interview.remaining_ms) : undefined
  const interviewRemaining =
    nested ?? (state.mode === 'interview' ? optionalNumber(payload.remaining_ms) : undefined)
  const interview =
    interviewRemaining === undefined
      ? state.interview
      : { ...state.interview, remainingMs: interviewRemaining }

  return {
    ...state,
    elapsedMs,
    remainingMs,
    disciplineLevel,
    students,
    interview,
  }
}

function applySessionEvent(state: ClassroomState, event: ServerEvent): ClassroomState {
  const payload = event.payload

  switch (event.type) {
    case 'session.started':
      return { ...state, status: 'in_progress' }

    case 'session.time_tick':
      return applyTimeTick(state, payload)

    case 'session.ended':
      return {
        ...state,
        status: 'ended',
        endedReason: nullableString(payload.reason),
        elapsedMs: optionalNumber(payload.duration_ms) ?? state.elapsedMs,
        dialogue: null,
        activeStudentId: null,
      }

    case 'session.failed':
      return {
        ...state,
        status: 'failed',
        failureReason: nullableString(payload.reason),
        failureCode: nullableString(payload.error_code),
        dialogue: null,
        activeStudentId: null,
      }

    case 'session.resumed':
      return state

    case 'recording.ready_ack': {
      const required = stringArray(payload.required_streams).filter(isStreamType)
      const existing = state.recording
      return {
        ...state,
        recording: {
          required_streams: required.length > 0 ? required : (existing?.required_streams ?? []),
          streams: existing?.streams ?? {},
          ready: payload.ok === true,
        },
      }
    }

    case 'submission.created':
      return { ...state, submissionId: nullableString(payload.submission_id) }

    default:
      return state
  }
}

function applyInterviewEvent(state: ClassroomState, event: ServerEvent): ClassroomState {
  const payload = event.payload
  const interview = state.interview

  switch (event.type) {
    case 'interview.question_shown': {
      const current = snapshotQuestion(payload)
      const remainingMs = optionalNumber(payload.remaining_ms)
      return {
        ...state,
        interview: {
          ...interview,
          current: current ?? interview.current,
          answerId: null,
          answering: false,
          ended: false,
          remainingMs: remainingMs ?? interview.remainingMs,
        },
      }
    }

    case 'interview.answer_started':
      return {
        ...state,
        interview: {
          ...interview,
          answerId: nullableString(payload.answer_id) ?? interview.answerId,
          answering: true,
        },
      }

    case 'interview.answer_ended':
      return {
        ...state,
        interview: {
          ...interview,
          answerId: nullableString(payload.answer_id) ?? interview.answerId,
          answering: false,
        },
      }

    case 'interview.next_question': {
      const order = optionalNumber(payload.order)
      const nextCurrent =
        interview.current && order !== undefined
          ? { ...interview.current, question_id: nullableString(payload.question_id) ?? interview.current.question_id, order }
          : interview.current
      return {
        ...state,
        interview: {
          ...interview,
          current: nextCurrent,
          answerId: null,
          answering: false,
        },
      }
    }

    case 'interview.ended':
      return {
        ...state,
        interview: {
          ...interview,
          answerId: null,
          answering: false,
          ended: true,
          remainingMs: 0,
        },
      }

    default:
      return state
  }
}

function applyControlEvent(state: ClassroomState, event: ServerEvent): ClassroomState {
  if (event.type !== 'error') {
    return state
  }
  const payload = event.payload as EventPayload
  return {
    ...state,
    lastError: {
      code: stringValue(payload.code, 'UNKNOWN'),
      message: stringValue(payload.message),
      commandId: nullableString(payload.command_id),
    },
  }
}

// ---------------------------------------------------------------------------
// Public entry point
// ---------------------------------------------------------------------------

export function classroomReducer(state: ClassroomState, event: ServerEvent): ClassroomState {
  switch (event.type) {
    case 'session.snapshot':
      return applySnapshot(state, event)

    case 'classroom.state_changed':
      return applyClassroomStateChanged(state, event.payload)

    case 'classroom.question_asked':
      return {
        ...state,
        classroom: {
          ...state.classroom,
          questionIntent: nullableString(event.payload.intent),
          questionSource: nullableString(event.payload.source),
        },
      }

    case 'classroom.question_suggested':
      return {
        ...state,
        classroom: {
          ...state.classroom,
          questionSuggestedConfidence:
            optionalNumber(event.payload.confidence) ?? state.classroom.questionSuggestedConfidence,
        },
      }

    case 'classroom.qa_ended':
      return {
        ...state,
        classroom: {
          ...state.classroom,
          qaPhase: null,
          questionIntent: null,
          questionSource: null,
        },
      }

    case 'classroom.hand_window_timeout': {
      let next = state
      for (const handRaiseId of stringArray(event.payload.hand_raise_ids)) {
        const studentId = next.handRaises[handRaiseId]
        if (studentId) {
          next = clearHandRaise(next, studentId, handRaiseId)
        }
      }
      return {
        ...next,
        classroom: {
          ...next.classroom,
          qaPhase: null,
          questionIntent: null,
          questionSource: null,
        },
      }
    }

    case 'classroom.discipline_level_changed':
      return {
        ...state,
        disciplineLevel:
          optionalNumber(event.payload.to) ??
          optionalNumber(event.payload.discipline_level) ??
          state.disciplineLevel,
      }

    case 'student.state_changed':
    case 'student.hand_raised':
    case 'student.hand_lowered':
    case 'student.question':
    case 'student.answer':
    case 'student.follow_up':
      return applyStudentEvent(state, event)

    case 'teacher.called_on':
    case 'teacher.marked':
    case 'teacher.management_action':
      return applyTeacherEvent(state, event)

    case 'dialogue.started':
    case 'dialogue.ended':
      return applyDialogueEvent(state, event)

    case 'discipline.event':
      return {
        ...state,
        disciplineLevel: numberValue(event.payload.level, state.disciplineLevel),
        lastDisciplineEvent: {
          level: numberValue(event.payload.level, state.disciplineLevel),
          studentIds: stringArray(event.payload.student_ids),
          eventType: nullableString(event.payload.event_type),
        },
      }

    case 'session.started':
    case 'session.time_tick':
    case 'session.ended':
    case 'session.failed':
    case 'session.resumed':
    case 'recording.ready_ack':
    case 'submission.created':
      return applySessionEvent(state, event)

    case 'interview.question_shown':
    case 'interview.answer_started':
    case 'interview.answer_ended':
    case 'interview.next_question':
    case 'interview.ended':
      return applyInterviewEvent(state, event)

    case 'error':
      return applyControlEvent(state, event)

    default:
      // `ping`, `server.ack`, `perception.*` and any future event type are
      // ignored by the classroom reducer.
      return state
  }
}