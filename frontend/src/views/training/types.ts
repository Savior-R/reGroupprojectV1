/** M6 wire types (08 §10). Backend JSON is snake_case. */

export type TrainingLevel = 'progressive' | 'mock_exam'

export interface VirtualStudentConfig {
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

export interface EditableStudent extends VirtualStudentConfig {
  key: string
}

export interface StudentTemplateCard {
  template_id: string
  topic: string
  course_id: string
  course_name: string
  training_level: TrainingLevel
  duration_ms: number
  virtual_student_count: number
}

export interface StudentTemplateDetail {
  template_id: string
  topic: string
  course_id: string
  course_name: string
  training_level: TrainingLevel
  duration_ms: number
  dimensions: string[]
  virtual_students: VirtualStudentConfig[]
}

export interface PublicTrainingConfig {
  topic: string
  training_level: TrainingLevel
  recommended_duration_ms: number
  max_duration_ms: number
  virtual_student_count: number
  virtual_students: VirtualStudentConfig[]
}

export type TrainingSessionStatus =
  | 'preparing'
  | 'in_progress'
  | 'ended'
  | 'reviewing'
  | 'selected'
  | 'evaluated'
  | 'cancelled'
  | 'failed'

export interface TrainingSessionView {
  session_id: string
  status: TrainingSessionStatus
  template_id: string | null
  course_id: string | null
  training_level: TrainingLevel
  recommended_duration_ms: number
  max_duration_ms: number
  public_config: PublicTrainingConfig
  created_at: string
  started_at: string | null
  ended_at: string | null
}

export interface TrainingMaterialView {
  material_id: string
  filename: string
  char_count: number
  preview: string
  expires_at: string
}

export interface TrainingRecordView {
  record_id: string
  session_id: string
  template_id: string | null
  topic: string
  training_level: TrainingLevel
  selected_at: string
  expires_at: string
  status: 'selected' | 'evaluated' | 'deleted' | 'expired'
  evaluation_status: string
  total_score: number | null
  report_available: boolean
  remaining_days: number
}

export interface TrainingRecordSelection {
  record: TrainingRecordView
  deleted_record_id: string | null
}

export interface DimensionScore {
  dimension: string
  score: number | null
  confidence: number | null
  sub_scores_json: string | null
}

export interface TrainingIssue {
  dimension: string
  start_ms: number | null
  end_ms: number | null
  severity: string
  evidence: string
  suggestion: string
  is_suggestion: boolean
}

export interface TrainingReport {
  record_id: string
  evaluation_id: string
  status: string
  total_score: number | null
  dimensions: DimensionScore[]
  issues: TrainingIssue[]
  warnings: string[]
  completed_at: string | null
}

export interface TrainingTemplateView {
  template_id: string
  source_task_id: string
  source_task_config_version: number
  course_id: string
  training_level: TrainingLevel
  status: 'published' | 'unpublished'
  topic: string
  duration_seconds: number
  max_duration_seconds: number
  virtual_student_count: number
  has_reference_material: boolean
  weights: Record<string, number>
  published_at: string | null
  unpublished_at: string | null
  created_at: string
}

export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}
