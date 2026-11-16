/**
 * Wire-level M4 report types. Backend JSON is snake_case (02 §1), so the
 * interfaces keep the wire names instead of camelCase.
 */

export type EvaluationStatus = 'pending' | 'running' | 'completed' | 'partial' | 'failed'
export type EvaluationMode = 'free' | 'scenario' | 'interview'
export type DimensionName = 'content' | 'expression' | 'interaction' | 'board' | 'time'
export type Severity = 'high' | 'medium' | 'low'

export interface EvaluationView {
  evaluation_id: string
  source_type: string
  source_id: string
  submission_id: string | null
  training_record_id: string | null
  session_id: string
  task_id: string | null
  course_id: string | null
  student_account_id: string | null
  mode: EvaluationMode
  status: EvaluationStatus
  total_score: number | null
  weights: Record<string, number>
  warnings: string[]
  partial_reasons: string[]
  error_code: string | null
  error_message: string | null
  model_version: string | null
  prompt_version: string | null
  rubric_version: string | null
  attempt_count: number
  started_at: string | null
  created_at: string
  completed_at: string | null
  updated_at: string | null
}

export interface IssueView {
  issue_id: string
  dimension: DimensionName
  start_ms: number | null
  end_ms: number | null
  severity: Severity
  evidence: string
  suggestion: string
  is_suggestion: boolean
  suggestion_rank: number | null
}

export interface ReportDimension {
  dimension: DimensionName
  score: number
  sub_scores: Record<string, number>
  sub_confidences: Record<string, number>
  confidence: number
}

export interface RadarView {
  axes: DimensionName[]
  values: (number | null)[]
}

export interface ReportMeta {
  model_version: string | null
  prompt_version: string | null
  rubric_version: string | null
  config_version: number | null
  attempt_count: number
  created_at: string | null
  started_at: string | null
  completed_at: string | null
}

export interface ReportView {
  evaluation_id: string
  submission_id: string | null
  training_record_id: string | null
  session_id: string
  mode: EvaluationMode
  status: EvaluationStatus
  total_score: number | null
  weights: Record<string, number>
  radar: RadarView
  dimensions: ReportDimension[]
  missing_dimensions: DimensionName[]
  suggestions: IssueView[]
  warnings: string[]
  partial_reasons: string[]
  meta: ReportMeta
}
