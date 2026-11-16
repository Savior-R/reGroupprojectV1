import { apiClient } from '../../api/client'
import type {
  PageResult,
  StudentTemplateCard,
  StudentTemplateDetail,
  TrainingLevel,
  TrainingMaterialView,
  TrainingRecordSelection,
  TrainingRecordView,
  TrainingReport,
  TrainingSessionView,
  TrainingTemplateView,
  VirtualStudentConfig,
} from './types'

/** M6 API module lives with the views until the shared API owner registers it. */

export interface CreateSessionPayload {
  template_id: string | null
  training_level: TrainingLevel
  course_id: string | null
  topic: string | null
  virtual_students: VirtualStudentConfig[]
  material_id: string | null
}

export function listStudentTemplates(
  level: TrainingLevel | null,
  courseId: string | null,
  page = 1,
  size = 20,
): Promise<PageResult<StudentTemplateCard>> {
  return apiClient
    .get<PageResult<StudentTemplateCard>>('/student/training/templates', {
      params: { level: level ?? undefined, course_id: courseId ?? undefined, page, size },
    })
    .then((response) => response.data)
}

export function getStudentTemplate(templateId: string): Promise<StudentTemplateDetail> {
  return apiClient
    .get<StudentTemplateDetail>(
      `/student/training/templates/${encodeURIComponent(templateId)}`,
    )
    .then((response) => response.data)
}

export function uploadTrainingMaterial(file: File): Promise<TrainingMaterialView> {
  const body = new FormData()
  body.append('file', file)
  return apiClient
    .post<TrainingMaterialView>('/training/materials', body)
    .then((response) => response.data)
}

export async function deleteTrainingMaterial(materialId: string): Promise<void> {
  await apiClient.delete(`/training/materials/${encodeURIComponent(materialId)}`)
}

export function createTrainingSession(
  payload: CreateSessionPayload,
): Promise<TrainingSessionView> {
  return apiClient
    .post<TrainingSessionView>('/training/sessions', payload)
    .then((response) => response.data)
}

export function getTrainingSession(sessionId: string): Promise<TrainingSessionView> {
  return apiClient
    .get<TrainingSessionView>(`/training/sessions/${encodeURIComponent(sessionId)}`)
    .then((response) => response.data)
}

export function endTrainingSession(sessionId: string): Promise<TrainingSessionView> {
  return apiClient
    .post<TrainingSessionView>(
      `/training/sessions/${encodeURIComponent(sessionId)}/end`,
    )
    .then((response) => response.data)
}

export async function cancelTrainingSession(sessionId: string): Promise<void> {
  await apiClient.post(`/training/sessions/${encodeURIComponent(sessionId)}/cancel`)
}

export function selectTrainingRecord(sessionId: string): Promise<TrainingRecordSelection> {
  return apiClient
    .post<TrainingRecordSelection>('/training/records', { session_id: sessionId })
    .then((response) => response.data)
}

export function listTrainingRecords(
  page = 1,
  size = 50,
): Promise<PageResult<TrainingRecordView>> {
  return apiClient
    .get<PageResult<TrainingRecordView>>('/training/records', { params: { page, size } })
    .then((response) => response.data)
}

export function getTrainingRecord(recordId: string): Promise<TrainingRecordView> {
  return apiClient
    .get<TrainingRecordView>(`/training/records/${encodeURIComponent(recordId)}`)
    .then((response) => response.data)
}

export async function deleteTrainingRecord(recordId: string): Promise<void> {
  await apiClient.delete(`/training/records/${encodeURIComponent(recordId)}`)
}

export function getTrainingReport(recordId: string): Promise<TrainingReport> {
  return apiClient
    .get<TrainingReport>(`/training/records/${encodeURIComponent(recordId)}/evaluation`)
    .then((response) => response.data)
}

export function listTeacherTemplates(
  level: TrainingLevel | null,
  courseId: string | null,
  page = 1,
  size = 50,
): Promise<PageResult<TrainingTemplateView>> {
  return apiClient
    .get<PageResult<TrainingTemplateView>>('/training/templates', {
      params: {
        training_level: level ?? undefined,
        course_id: courseId ?? undefined,
        page,
        size,
      },
    })
    .then((response) => response.data)
}

export function publishTrainingTemplate(payload: {
  source_task_id: string
  course_id: string
  training_level: TrainingLevel
}): Promise<TrainingTemplateView> {
  return apiClient
    .post<TrainingTemplateView>('/training/templates', payload)
    .then((response) => response.data)
}

export async function unpublishTrainingTemplate(templateId: string): Promise<void> {
  await apiClient.post(`/training/templates/${encodeURIComponent(templateId)}/unpublish`)
}
