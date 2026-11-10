import type { EditableStudent, VirtualStudentConfig } from './types'

const RANDOM_NAMES = [
  '林小雨',
  '陈嘉豪',
  '苏念',
  '周子涵',
  '许明远',
  '沈知夏',
  '顾一诺',
  '唐可欣',
  '陆星河',
  '叶清和',
]

export function newStudent(seatNo: number): EditableStudent {
  return {
    key: crypto.randomUUID(),
    name: RANDOM_NAMES[(seatNo - 1) % RANDOM_NAMES.length] ?? `学生${seatNo}`,
    avatar: `avatar-${String(((seatNo - 1) % 6) + 1).padStart(2, '0')}`,
    personality_text: '',
    activity: 0.5,
    question_rate: 0.5,
    ability: 0.5,
    attention_baseline: 0.5,
    discipline_probability: 0.5,
    seat_no: seatNo,
    sort_order: seatNo,
  }
}

export function defaultStudents(): EditableStudent[] {
  return [newStudent(1), newStudent(2), newStudent(3)]
}

export function fromWireStudents(
  students: VirtualStudentConfig[],
): EditableStudent[] {
  const source = students.length ? students : defaultStudents()
  return source.map((student) => ({
    ...student,
    key: crypto.randomUUID(),
  }))
}

export function toWireStudent(student: EditableStudent): VirtualStudentConfig {
  return {
    name: student.name,
    avatar: student.avatar,
    personality_text: student.personality_text,
    activity: student.activity,
    question_rate: student.question_rate,
    ability: student.ability,
    attention_baseline: student.attention_baseline,
    discipline_probability: student.discipline_probability,
    seat_no: student.seat_no,
    sort_order: student.sort_order,
  }
}

export function randomizeStudents(students: EditableStudent[]): EditableStudent[] {
  return students.map((student, index) => ({
    ...student,
    name: RANDOM_NAMES[Math.floor(Math.random() * RANDOM_NAMES.length)] ?? student.name,
    avatar: `avatar-${String(((index + Math.floor(Math.random() * 6)) % 6) + 1).padStart(2, '0')}`,
    seat_no: index + 1,
    sort_order: index + 1,
  }))
}
