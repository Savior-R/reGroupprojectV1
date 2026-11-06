import { describe, expect, it } from 'vitest'
import type { ServerEvent } from '../types/session'
import { classroomReducer, createInitialClassroomState } from './classroomReducer'

let eventId = 0

function serverEvent(
  type: ServerEvent['type'],
  seq: number,
  payload: Record<string, unknown> = {},
): ServerEvent {
  eventId += 1
  return {
    v: 1,
    type,
    event_id: `event-${eventId}`,
    seq,
    at_ms: seq * 100,
    session_id: 'session-1',
    payload,
  }
}

function snapshotEvent(overrides: Record<string, unknown> = {}): ServerEvent {
  return serverEvent('session.snapshot', 7, {
    state: 'in_progress',
    mode: 'free',
    public_config: { topic: '分数', max_seconds: 600 },
    virtual_students: [
      {
        virtual_student_id: 's1',
        name: '小明',
        avatar: null,
        seat_no: 1,
        sort_order: 1,
        state: 'focused',
        attention: 0.8,
        hand_raise_energy: 0.5,
        // Privileged fields that must never leak into the UI state.
        params: { ability: 0.9 },
        personality: 'secret-personality',
        reference_material: 'secret-material',
      },
    ],
    interview: {},
    recording: {
      required_streams: ['camera', 'screen', 'audio'],
      streams: {},
      ready: false,
    },
    last_seq: 7,
    elapsed_ms: 1000,
    answer_points: ['secret-answer-point'],
    rubric: { weights: [0.5, 0.5] },
    ...overrides,
  })
}

function stateWithSnapshot() {
  return classroomReducer(createInitialClassroomState(), snapshotEvent())
}

describe('classroomReducer', () => {
  it('applies the snapshot without privileged fields', () => {
    const state = stateWithSnapshot()

    expect(state.status).toBe('in_progress')
    expect(state.mode).toBe('free')
    expect(state.publicConfig.topic).toBe('分数')
    expect(state.lastSeq).toBe(7)
    expect(state.remainingMs).toBe(599_000)
    expect(state.students.s1).toBeDefined()
    expect(state.students.s1!.state).toBe('focused')
    expect(state.students.s1!.hand_raise_id).toBeNull()

    // Only public virtual-student fields are copied.
    expect(Object.keys(state.students.s1!).sort()).toEqual(
      [
        'attention',
        'avatar',
        'hand_raise_energy',
        'hand_raise_id',
        'name',
        'seat_no',
        'sort_order',
        'state',
        'virtual_student_id',
      ].sort(),
    )
    expect(state.students.s1).not.toHaveProperty('params')
    expect(state.students.s1).not.toHaveProperty('personality')
    expect(state.students.s1).not.toHaveProperty('reference_material')
    expect(JSON.stringify(state)).not.toContain('secret')

    // Transient fields keep their fresh defaults.
    expect(state.handRaises).toEqual({})
    expect(state.activeStudentId).toBeNull()
    expect(state.marks).toEqual({})
    expect(state.dialogue).toBeNull()
  })

  it('preserves event-derived fields when a reconnect snapshot omits them', () => {
    let state = classroomReducer(
      createInitialClassroomState(),
      snapshotEvent({
        virtual_students: [
          {
            virtual_student_id: 's1',
            name: '小明',
            state: 'focused',
            attention: 0.8,
            hand_raise_energy: 0.5,
          },
          {
            virtual_student_id: 's2',
            name: '小红',
            state: 'focused',
            attention: 0.7,
            hand_raise_energy: 0.4,
          },
        ],
      }),
    )

    state = classroomReducer(
      state,
      serverEvent('classroom.state_changed', 8, {
        from: 'lecturing',
        to: 'qa',
        qa_phase: 'waiting_hands',
        trigger: 'teacher.ask_class',
      }),
    )
    state = classroomReducer(
      state,
      serverEvent('student.hand_raised', 9, { student_id: 's1', hand_raise_id: 'h1' }),
    )
    state = classroomReducer(
      state,
      serverEvent('teacher.called_on', 10, { student_id: 's2' }),
    )
    state = classroomReducer(
      state,
      serverEvent('dialogue.started', 11, { dialogue_id: 'd1', student_id: 's2' }),
    )
    state = classroomReducer(
      state,
      serverEvent('teacher.marked', 12, { answer_id: 'a1', correctness: 'correct' }),
    )
    state = classroomReducer(
      state,
      serverEvent('session.time_tick', 13, {
        elapsed_ms: 30_000,
        remaining_ms: 570_000,
        discipline_level: 3,
        states: [],
      }),
    )

    expect(state.handRaises).toEqual({ h1: 's1' })
    expect(state.activeStudentId).toBe('s2')
    expect(state.marks).toEqual({ a1: 'correct' })
    expect(state.disciplineLevel).toBe(3)
    expect(state.classroom.macroState).toBe('qa')
    expect(state.dialogue).toEqual({ dialogueId: 'd1', studentId: 's2' })

    // Reconnect snapshot: it carries the durable view but none of the
    // transient fields set above.
    state = classroomReducer(
      state,
      snapshotEvent({
        public_config: { topic: '新主题', max_seconds: 900 },
        virtual_students: [
          {
            virtual_student_id: 's1',
            name: '小明',
            state: 'hand_raised',
            attention: 0.6,
            hand_raise_energy: 0.3,
          },
          {
            virtual_student_id: 's2',
            name: '小红',
            state: 'dialogue',
            attention: 0.6,
            hand_raise_energy: 0.3,
          },
        ],
        recording: {
          required_streams: ['camera', 'screen', 'audio'],
          streams: { camera: 'uploading' },
          ready: true,
        },
        last_seq: 20,
        elapsed_ms: 45_000,
      }),
    )

    // Event-derived state survives the reconnect snapshot.
    expect(state.handRaises).toEqual({ h1: 's1' })
    expect(state.students.s1!.hand_raise_id).toBe('h1')
    expect(state.activeStudentId).toBe('s2')
    expect(state.marks).toEqual({ a1: 'correct' })
    expect(state.disciplineLevel).toBe(3)
    expect(state.classroom.macroState).toBe('qa')
    expect(state.classroom.qaPhase).toBe('waiting_hands')
    expect(state.dialogue).toEqual({ dialogueId: 'd1', studentId: 's2' })

    // Snapshot-provided fields still update.
    expect(state.publicConfig.topic).toBe('新主题')
    expect(state.elapsedMs).toBe(45_000)
    expect(state.lastSeq).toBe(20)
    expect(state.recording?.ready).toBe(true)
    expect(state.students.s1!.attention).toBe(0.6)
  })

  it('advances lastSeq as applied events arrive', () => {
    let state = stateWithSnapshot()
    expect(state.lastSeq).toBe(7)

    state = classroomReducer(
      state,
      serverEvent('student.hand_raised', 9, { student_id: 's1', hand_raise_id: 'h1' }),
    )
    expect(state.lastSeq).toBe(9)

    state = classroomReducer(
      state,
      serverEvent('session.time_tick', 12, {
        elapsed_ms: 10_000,
        remaining_ms: 590_000,
        discipline_level: 0,
        states: [],
      }),
    )
    expect(state.lastSeq).toBe(12)
  })

  it('tracks hand raise and called-on states', () => {
    let state = stateWithSnapshot()

    state = classroomReducer(
      state,
      serverEvent('classroom.state_changed', 8, {
        from: 'lecturing',
        to: 'qa',
        qa_phase: 'waiting_hands',
        trigger: 'teacher.ask_class',
      }),
    )
    expect(state.classroom.macroState).toBe('qa')
    expect(state.classroom.qaPhase).toBe('waiting_hands')

    state = classroomReducer(
      state,
      serverEvent('student.hand_raised', 9, {
        student_id: 's1',
        hand_raise_id: 'h1',
      }),
    )
    expect(state.students.s1!.state).toBe('hand_raised')
    expect(state.students.s1!.hand_raise_id).toBe('h1')
    expect(state.handRaises).toEqual({ h1: 's1' })

    state = classroomReducer(
      state,
      serverEvent('teacher.called_on', 10, {
        student_id: 's1',
        hand_raise_id: 'h1',
      }),
    )
    expect(state.students.s1!.state).toBe('called_on')
    expect(state.students.s1!.hand_raise_id).toBeNull()
    expect(state.handRaises).toEqual({})
    expect(state.activeStudentId).toBe('s1')

    state = classroomReducer(
      state,
      serverEvent('student.hand_lowered', 11, {
        student_id: 's1',
        hand_raise_id: 'h2',
        reason: 'manual',
      }),
    )
    expect(state.students.s1!.hand_raise_id).toBeNull()
  })

  it('clears open hands when the hand window times out', () => {
    let state = stateWithSnapshot()
    state = classroomReducer(
      state,
      serverEvent('student.hand_raised', 8, { student_id: 's1', hand_raise_id: 'h1' }),
    )
    state = classroomReducer(
      state,
      serverEvent('classroom.hand_window_timeout', 9, { hand_raise_ids: ['h1'] }),
    )

    expect(state.students.s1!.hand_raise_id).toBeNull()
    expect(state.students.s1!.state).toBe('focused')
    expect(state.handRaises).toEqual({})
    expect(state.classroom.qaPhase).toBeNull()
  })

  it('updates only elapsed/remaining/discipline/states on session.time_tick', () => {
    const before = stateWithSnapshot()

    const state = classroomReducer(
      before,
      serverEvent('session.time_tick', 8, {
        elapsed_ms: 5000,
        remaining_ms: 595_000,
        discipline_level: 2,
        states: [
          {
            virtual_student_id: 's1',
            state: 'distracted',
            attention: 0.2,
            hand_raise_energy: 0.1,
          },
        ],
      }),
    )

    expect(state.elapsedMs).toBe(5000)
    expect(state.remainingMs).toBe(595_000)
    expect(state.disciplineLevel).toBe(2)
    expect(state.students.s1!.state).toBe('distracted')
    expect(state.students.s1!.attention).toBe(0.2)
    expect(state.students.s1!.hand_raise_energy).toBe(0.1)
    // Untouched fields keep their previous values/references.
    expect(state.students.s1!.name).toBe('小明')
    expect(state.status).toBe('in_progress')
    expect(state.publicConfig).toBe(before.publicConfig)
    expect(state.interview).toBe(before.interview)
  })

  it('tracks the interview question and remaining time', () => {
    let state = classroomReducer(
      createInitialClassroomState(),
      snapshotEvent({
        mode: 'interview',
        public_config: { topic: '面试' },
        interview: { current: null, answer_id: null, remaining_ms: 120_000, ended: false },
      }),
    )
    expect(state.interview.remainingMs).toBe(120_000)

    state = classroomReducer(
      state,
      serverEvent('interview.question_shown', 8, {
        question_id: 'q1',
        order: 1,
        total: 3,
        text: '请自我介绍',
      }),
    )
    expect(state.interview.current).toEqual({
      question_id: 'q1',
      order: 1,
      total: 3,
      text: '请自我介绍',
    })
    expect(state.interview.ended).toBe(false)

    state = classroomReducer(
      state,
      serverEvent('interview.answer_started', 9, {
        question_id: 'q1',
        answer_id: 'a1',
      }),
    )
    expect(state.interview.answerId).toBe('a1')
    expect(state.interview.answering).toBe(true)

    state = classroomReducer(
      state,
      serverEvent('session.time_tick', 10, {
        elapsed_ms: 5000,
        remaining_ms: 115_000,
        discipline_level: 0,
        states: [],
      }),
    )
    expect(state.remainingMs).toBe(115_000)
    expect(state.interview.remainingMs).toBe(115_000)

    state = classroomReducer(
      state,
      serverEvent('interview.answer_ended', 11, {
        question_id: 'q1',
        answer_id: 'a1',
      }),
    )
    expect(state.interview.answering).toBe(false)

    state = classroomReducer(state, serverEvent('interview.ended', 12))
    expect(state.interview.ended).toBe(true)
    expect(state.interview.answering).toBe(false)
  })

  it('tracks session end and failure reasons', () => {
    let state = stateWithSnapshot()
    state = classroomReducer(
      state,
      serverEvent('session.ended', 8, { reason: 'manual', duration_ms: 60_000 }),
    )
    expect(state.status).toBe('ended')
    expect(state.endedReason).toBe('manual')
    expect(state.elapsedMs).toBe(60_000)

    let failed = stateWithSnapshot()
    failed = classroomReducer(
      failed,
      serverEvent('session.failed', 8, { reason: 'recording_lost', error_code: 'SESSION_FAILED' }),
    )
    expect(failed.status).toBe('failed')
    expect(failed.failureReason).toBe('recording_lost')
    expect(failed.failureCode).toBe('SESSION_FAILED')
  })

  it('ignores unknown events without mutating state', () => {
    const state = stateWithSnapshot()

    const unknown = {
      ...serverEvent('perception.screen_changed', 8, { frame_id: 'f1' }),
      type: 'totally.unknown' as ServerEvent['type'],
    }

    expect(classroomReducer(state, unknown)).toBe(state)
    expect(classroomReducer(state, serverEvent('perception.screen_changed', 9, {}))).toBe(state)
  })
})