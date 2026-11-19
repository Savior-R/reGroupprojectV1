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

export type TaskStatus = 'published' | 'closed' | 'deleted'

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

// ---------------------------------------------------------------------------
// Teacher task management (05C §9.1—§9.6)
// ---------------------------------------------------------------------------

export interface TeacherVirtualStudent {
  virtual_student_id?: string
  name: string
  avatar: string
  personality_text: string
  activity: number
  question_rate: number
  ability: number
  attention_baseline: number
  discipline_probability: number
  seat_no: number
  sort_order: number
}

export interface TaskReferenceMaterial {
  material_id: string
  filename: string
  size: number
  mime: string
  char_count: number
  parse_status: string
  preview: string
}

export interface TaskScenario {
  role: string | null
  situation: string | null
}

export interface TaskQuestionConfig {
  question_ids: string[]
  total_duration_seconds: number
}

export interface TaskPublishedQuestion {
  question_id: string
  order: number
  stem: string
  answer_points: string
  category_id: string
  category_name: string
}

export interface TaskCardView {
  task_id: string
  course_id: string
  mode: TaskMode
  topic: string
  duration_seconds: number
  question_count: number
  virtual_student_count: number
  status: TaskStatus
  assigned_count: number
  submitted_count: number
  published_at: string | null
  updated_at: string
}

export interface TeacherTaskView {
  task_id: string
  course_id: string
  mode: TaskMode
  topic: string
  textbook_version: string | null
  duration_recommended_seconds: number
  duration_max_seconds: number
  rubric_weights: Record<string, number>
  reference_material: TaskReferenceMaterial | null
  scenario: TaskScenario | null
  virtual_students: TeacherVirtualStudent[]
  question_config: TaskQuestionConfig | null
  published_questions: TaskPublishedQuestion[]
  status: TaskStatus
  config_version: number
  published_at: string | null
  closed_at: string | null
  created_at: string
  updated_at: string
}

export interface MaterialUploadView extends TaskReferenceMaterial {
  parsed_text: string | null
}

export interface TaskWritePayload {
  mode: TaskMode
  topic: string
  textbook_version?: string | null
  duration_recommended_seconds?: number
  duration_max_seconds?: number
  rubric_weights?: Record<string, number>
  scenario?: TaskScenario | null
  virtual_students?: TeacherVirtualStudent[]
  question_config?: TaskQuestionConfig | null
}

export interface TeacherTaskQuery {
  status?: TaskStatus | ''
  mode?: TaskMode | ''
  page?: number
  size?: number
}

export function listCourseTasks(
  courseId: string,
  query: TeacherTaskQuery = {},
): Promise<PageResult<TaskCardView>> {
  return apiClient
    .get<PageResult<TaskCardView>>(`/courses/${encodeURIComponent(courseId)}/tasks`, {
      params: {
        status: query.status || undefined,
        mode: query.mode || undefined,
        page: query.page ?? 1,
        size: query.size ?? 50,
      },
    })
    .then((response) => response.data)
}

export function getTeacherTask(taskId: string): Promise<TeacherTaskView> {
  return apiClient
    .get<TeacherTaskView>(`/tasks/${encodeURIComponent(taskId)}`)
    .then((response) => response.data)
}

function taskFormData(
  payload: Partial<TaskWritePayload>,
  material?: File | null,
): FormData {
  const body = new FormData()
  body.append(
    'config',
    new Blob([JSON.stringify(payload)], { type: 'application/json' }),
  )
  if (material) {
    body.append('material', material)
  }
  return body
}

export function createTask(
  courseId: string,
  payload: TaskWritePayload,
  material?: File | null,
): Promise<TeacherTaskView> {
  return apiClient
    .post<TeacherTaskView>(
      `/courses/${encodeURIComponent(courseId)}/tasks`,
      taskFormData(payload, material),
    )
    .then((response) => response.data)
}

export function updateTask(
  taskId: string,
  payload: Partial<TaskWritePayload>,
  material?: File | null,
): Promise<TeacherTaskView> {
  return apiClient
    .patch<TeacherTaskView>(
      `/tasks/${encodeURIComponent(taskId)}`,
      taskFormData(payload, material),
    )
    .then((response) => response.data)
}

export function closeTask(taskId: string): Promise<TeacherTaskView> {
  return apiClient
    .post<TeacherTaskView>(`/tasks/${encodeURIComponent(taskId)}/close`)
    .then((response) => response.data)
}

export async function deleteTask(taskId: string): Promise<void> {
  await apiClient.delete(`/tasks/${encodeURIComponent(taskId)}`)
}

export function uploadTaskMaterial(
  taskId: string,
  file: File,
): Promise<MaterialUploadView> {
  const body = new FormData()
  body.append('file', file)
  return apiClient
    .post<MaterialUploadView>(`/tasks/${encodeURIComponent(taskId)}/material`, body)
    .then((response) => response.data)
}

export function getTaskMaterial(taskId: string): Promise<MaterialUploadView> {
  return apiClient
    .get<MaterialUploadView>(`/tasks/${encodeURIComponent(taskId)}/material`)
    .then((response) => response.data)
}

export async function deleteTaskMaterial(taskId: string): Promise<void> {
  await apiClient.delete(`/tasks/${encodeURIComponent(taskId)}/material`)
}