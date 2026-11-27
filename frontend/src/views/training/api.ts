import { apiClient } from '../../api/client'
import type { IssueView, ReportView } from '../report/types'
import type {
  PageResult,
  StudentTemplateCard,
  StudentTemplateDetail,
  TaskTemplateConfigView,
  TrainingLevel,
  TrainingMaterialView,
  TrainingOwnerRole,
  TrainingPublicationStatus,
  TrainingPublicationView,
  TrainingRecordSelection,
  TrainingRecordView,
  TrainingReport,
  TrainingSessionView,
  TrainingTemplateDetail,
  TrainingTemplateStatus,
  TrainingTemplateSummary,
  VirtualStudentConfig,
} from './types'

/** M6 API module lives with the views until the shared API owner registers it. */

export interface CreateSessionPayload {
  template_id: string | null
  publication_id: string | null
  training_level: TrainingLevel
  course_id: string | null
  topic: string | null
  virtual_students: VirtualStudentConfig[]
  material_id: string | null
}

export interface TrainingTemplatePayload {
  owner_role?: TrainingOwnerRole
  name: string
  training_level: TrainingLevel
  topic: string
  textbook_version: string | null
  duration: { recommended_seconds: number; max_seconds: number }
  weights: Record<string, number>
  virtual_students: VirtualStudentConfig[]
  material_id: string | null
}

export interface UpdateTrainingTemplatePayload {
  name?: string
  training_level?: TrainingLevel
  topic?: string
  textbook_version?: string | null
  duration?: { recommended_seconds: number; max_seconds: number }
  weights?: Record<string, number>
  virtual_students?: VirtualStudentConfig[]
  material_id?: string | null
  remove_material?: boolean
}

export interface MyTemplateQuery {
  role?: TrainingOwnerRole
  level?: TrainingLevel
  status?: TrainingTemplateStatus
  page?: number
  size?: number
}

export interface PublicationQuery {
  templateId?: string
  courseId?: string
  status?: TrainingPublicationStatus
  page?: number
  size?: number
}

// ---------------------------------------------------------------- student visible

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

export function getStudentTemplate(
  publicationId: string,
): Promise<StudentTemplateDetail> {
  return apiClient
    .get<StudentTemplateDetail>(
      `/student/training/templates/${encodeURIComponent(publicationId)}`,
    )
    .then((response) => response.data)
}

// ---------------------------------------------------------------- personal templates

export function listMyTemplates(
  query: MyTemplateQuery = {},
): Promise<PageResult<TrainingTemplateSummary>> {
  return apiClient
    .get<PageResult<TrainingTemplateSummary>>('/training/my-templates', {
      params: {
        role: query.role,
        level: query.level,
        status: query.status,
        page: query.page ?? 1,
        size: query.size ?? 50,
      },
    })
    .then((response) => response.data)
}

export function getMyTemplate(templateId: string): Promise<TrainingTemplateDetail> {
  return apiClient
    .get<TrainingTemplateDetail>(
      `/training/my-templates/${encodeURIComponent(templateId)}`,
    )
    .then((response) => response.data)
}

export function createMyTemplate(
  payload: TrainingTemplatePayload,
): Promise<TrainingTemplateDetail> {
  return apiClient
    .post<TrainingTemplateDetail>('/training/my-templates', payload)
    .then((response) => response.data)
}

export function updateMyTemplate(
  templateId: string,
  payload: UpdateTrainingTemplatePayload,
): Promise<TrainingTemplateDetail> {
  return apiClient
    .patch<TrainingTemplateDetail>(
      `/training/my-templates/${encodeURIComponent(templateId)}`,
      payload,
    )
    .then((response) => response.data)
}

export async function deleteMyTemplate(templateId: string): Promise<void> {
  await apiClient.delete(`/training/my-templates/${encodeURIComponent(templateId)}`)
}

export function copyTemplateFromPublication(
  publicationId: string,
  name: string | null,
): Promise<TrainingTemplateDetail> {
  return apiClient
    .post<TrainingTemplateDetail>('/training/my-templates/from-publication', {
      publication_id: publicationId,
      name,
    })
    .then((response) => response.data)
}

export function getTaskTemplateConfig(
  templateId: string,
): Promise<TaskTemplateConfigView> {
  return apiClient
    .get<TaskTemplateConfigView>(
      `/training/my-templates/${encodeURIComponent(templateId)}/task-config`,
    )
    .then((response) => response.data)
}

// ---------------------------------------------------------------- publications

export function listPublications(
  query: PublicationQuery = {},
): Promise<PageResult<TrainingPublicationView>> {
  return apiClient
    .get<PageResult<TrainingPublicationView>>('/training/publications', {
      params: {
        template_id: query.templateId,
        course_id: query.courseId,
        status: query.status,
        page: query.page ?? 1,
        size: query.size ?? 50,
      },
    })
    .then((response) => response.data)
}

export function publishTemplate(
  templateId: string,
  courseId: string,
): Promise<TrainingPublicationView> {
  return apiClient
    .post<TrainingPublicationView>(
      `/training/my-templates/${encodeURIComponent(templateId)}/publications`,
      { course_id: courseId },
    )
    .then((response) => response.data)
}

export async function unpublishPublication(publicationId: string): Promise<void> {
  await apiClient.post(
    `/training/publications/${encodeURIComponent(publicationId)}/unpublish`,
  )
}

// ---------------------------------------------------------------- materials

export function uploadTrainingMaterial(file: File): Promise<TrainingMaterialView> {
  const body = new FormData()
  body.append('file', file)
  return apiClient
    .post<TrainingMaterialView>('/training/materials', body)
    .then((response) => response.data)
}

export function uploadTemplateMaterial(file: File): Promise<TrainingMaterialView> {
  const body = new FormData()
  body.append('file', file)
  return apiClient
    .post<TrainingMaterialView>('/training/template-materials', body)
    .then((response) => response.data)
}

export async function deleteTrainingMaterial(
  materialId: string,
  usage: 'free_session' | 'template' = 'free_session',
): Promise<void> {
  const base = usage === 'template' ? '/training/template-materials' : '/training/materials'
  await apiClient.delete(`${base}/${encodeURIComponent(materialId)}`)
}

// ---------------------------------------------------------------- sessions

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

// ---------------------------------------------------------------- records

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
