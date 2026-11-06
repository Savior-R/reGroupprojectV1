/**
 * WebSocket event and command names from 05A §6 / 02 §6.
 *
 * Keeping the names in one union lets the reducer in Task 3 switch
 * exhaustively without importing server code.
 */

export type ServerEventType =
  // transport-level envelopes
  | 'session.snapshot'
  | 'server.ack'
  | 'error'
  | 'ping'
  // classroom
  | 'classroom.state_changed'
  | 'classroom.question_asked'
  | 'classroom.question_suggested'
  | 'classroom.hand_window_timeout'
  | 'classroom.qa_ended'
  | 'classroom.discipline_level_changed'
  // students / teacher / dialogue
  | 'student.state_changed'
  | 'student.hand_raised'
  | 'student.hand_lowered'
  | 'student.question'
  | 'student.answer'
  | 'student.follow_up'
  | 'teacher.called_on'
  | 'teacher.marked'
  | 'teacher.management_action'
  | 'dialogue.started'
  | 'dialogue.ended'
  | 'discipline.event'
  // session lifecycle
  | 'session.started'
  | 'session.time_tick'
  | 'session.ended'
  | 'session.failed'
  | 'session.resumed'
  | 'recording.ready_ack'
  // perception (screen only in this round)
  | 'perception.screen_changed'
  // interview
  | 'interview.question_shown'
  | 'interview.answer_started'
  | 'interview.answer_ended'
  | 'interview.next_question'
  | 'interview.ended'
  // M3 submission
  | 'submission.created'

export type ClientCommandType =
  | 'recording.ready'
  | 'session.start'
  | 'session.resume'
  | 'teacher.ask_class'
  | 'teacher.ask_student_question'
  | 'teacher.call_on'
  | 'teacher.mark_answer'
  | 'teacher.manage_student'
  | 'teacher.end_dialogue'
  | 'teacher.end_qa'
  | 'teacher.end_session'
  | 'interview.next_question'
  | 'interview.end'
  | 'time.sync'
  | 'pong'

export type EventPayload = Record<string, unknown>