import { apiClient } from '../../api/client'
import type { IssueView, ReportView } from '../report/types'
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

/**
 * M6 复用 M4 的训练报告接口（06 §9.2 / 08 §10.6），在 M6 边界把 M4 的 ReportView
 * 适配成页面现有的 TrainingReport 视图，避免两套后端 DTO 并存。
 */
export async function getTrainingReport(recordId: string): Promise<TrainingReport> {
  const report = await apiClient
    .get<ReportView>(`/training/records/${encodeURIComponent(recordId)}/evaluation`)
    .then((response) => response.data)

  const issues = await apiClient
    .get<IssueView[]>(`/evaluations/${encodeURIComponent(report.evaluation_id)}/issues`)
    .then((response) => response.data)

  return {
    record_id: report.training_record_id ?? recordId,
    evaluation_id: report.evaluation_id,
    status: report.status,
    total_score: report.total_score,
    dimensions: report.dimensions.map((dimension) => ({
      dimension: dimension.dimension,
      score: dimension.score,
      confidence: dimension.confidence,
      sub_scores_json: JSON.stringify(dimension.sub_scores),
    })),
    issues: issues.map((issue) => ({
      dimension: issue.dimension,
      start_ms: issue.start_ms,
      end_ms: issue.end_ms,
      severity: issue.severity,
      evidence: issue.evidence,
      suggestion: issue.suggestion,
      is_suggestion: issue.is_suggestion,
    })),
    warnings: report.warnings,
    completed_at: report.meta?.completed_at ?? null,
  }
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
