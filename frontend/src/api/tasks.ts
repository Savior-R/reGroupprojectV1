import { apiClient } from './client'
import type { PageResult } from '../types/api'

/**
 * Student task entry API (02 §4.3, 05C §9.7/§9.8).
 *
 * Only student-visible fields are modeled here. Weights, reference material,
 * answer points, virtual-student parameters and personality stay on the
 * teacher side of the wire.
 */

export type TaskMode = 'free' | 'scenario' | 'interview'

export type TaskStatus = 'draft' | 'published' | 'closed'

export type AssignmentStatus = 'assigned' | 'submitted'

export interface StudentVirtualStudentView {
  virtual_student_id: string
  name: string
  avatar: string | null
  seat_no: number
}

export interface StudentTaskView {
  task_id: string
  course_id: string
  course_name: string
  mode: TaskMode
  topic: string
  duration_seconds: number
  question_count: number
  virtual_students: StudentVirtualStudentView[]
  teacher_account_ids: string[]
  status: TaskStatus
  assignment_status: AssignmentStatus
}

/** Detail payload shares the public projection of the list row. */
export type StudentTaskDetailView = StudentTaskView

/**
 * `GET /student/tasks`. The backend names the page-size parameter `size`
 * (StudentTaskController), not `page_size`.
 */
export function listStudentTasks(page = 1, size = 20): Promise<PageResult<StudentTaskView>> {
  return apiClient
    .get<PageResult<StudentTaskView>>('/student/tasks', { params: { page, size } })
    .then((response) => response.data)
}

export function getStudentTask(taskId: string): Promise<StudentTaskDetailView> {
  return apiClient
    .get<StudentTaskDetailView>(`/student/tasks/${encodeURIComponent(taskId)}`)
    .then((response) => response.data)
}