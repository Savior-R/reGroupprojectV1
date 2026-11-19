import { apiClient } from '../../api/client'
import type { EvaluationView, IssueView, ReportView } from './types'

/**
 * M4 report endpoints (06 §9.1 / §9.2). Kept inside the report view folder
 * because `frontend/src/api/**` is owned by the Score/QA owner.
 */

export function getEvaluation(evaluationId: string): Promise<EvaluationView> {
  return apiClient
    .get<EvaluationView>(`/evaluations/${encodeURIComponent(evaluationId)}`)
    .then((response) => response.data)
}

export function getEvaluationBySubmission(submissionId: string): Promise<EvaluationView> {
  return apiClient
    .get<EvaluationView>(`/submissions/${encodeURIComponent(submissionId)}/evaluation`)
    .then((response) => response.data)
}

export function getEvaluationByTrainingRecord(recordId: string): Promise<ReportView> {
  return apiClient
    .get<ReportView>(`/training/records/${encodeURIComponent(recordId)}/evaluation`)
    .then((response) => response.data)
}

export function getReport(evaluationId: string): Promise<ReportView> {
  return apiClient
    .get<ReportView>(`/evaluations/${encodeURIComponent(evaluationId)}/report`)
    .then((response) => response.data)
}

export function getIssues(evaluationId: string): Promise<IssueView[]> {
  return apiClient
    .get<IssueView[]>(`/evaluations/${encodeURIComponent(evaluationId)}/issues`)
    .then((response) => response.data)
}
