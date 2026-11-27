/** M6 wire types (08 §4–§10, V1.6 personal-template model). Backend JSON is snake_case. */

export type TrainingLevel = 'progressive' | 'mock_exam'
export type TrainingTemplateStatus = 'active' | 'archived'
export type TrainingOwnerRole = 'teacher' | 'student'
export type TrainingTemplateSourceType = 'manual' | 'task' | 'template'
export type TrainingPublicationStatus = 'published' | 'unpublished'
export type TrainingUnpublishReason =
  | 'manual'
  | 'course_archived'
  | 'teacher_removed'
  | 'template_deleted'

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

/** Student-visible publication card (08 §10.3); no weights, material or owner. */
export interface StudentTemplateCard {
  publication_id: string
  name: string
  topic: string
  course_id: string
  course_name: string
  training_level: TrainingLevel
  duration_ms: number
  virtual_student_count: number
  copied: boolean
}

export interface StudentTemplateDetail {
  publication_id: string
  name: string
  topic: string
  course_id: string
  course_name: string
  training_level: TrainingLevel
  recommended_duration_ms: number
  max_duration_ms: number
  dimensions: string[]
  virtual_students: VirtualStudentConfig[]
  copied: boolean
}

export interface TrainingDuration {
  recommended_seconds: number
  max_seconds: number
}

export interface TrainingMaterialRef {
  filename: string
  mime: string
  size: number
  char_count: number
}

/** Owner-facing personal template list row (08 §10.1). */
export interface TrainingTemplateSummary {
  template_id: string
  owner_role: TrainingOwnerRole
  name: string
  training_level: TrainingLevel
  topic: string
  status: TrainingTemplateStatus
  source_type: TrainingTemplateSourceType
  published_course_count: number
  duration_seconds: number
  virtual_student_count: number
  updated_at: string
}

/** Owner-facing personal template detail (08 §4.6). */
export interface TrainingTemplateDetail {
  template_id: string
  school_id: string
  owner_role: TrainingOwnerRole
  name: string
  training_level: TrainingLevel
  topic: string
  textbook_version: string | null
  duration: TrainingDuration
  weights: Record<string, number>
  virtual_students: VirtualStudentConfig[]
  status: TrainingTemplateStatus
  source_type: TrainingTemplateSourceType
  source_template_id: string | null
  source_publication_id: string | null
  reference_material: TrainingMaterialRef | null
  published_course_count: number
  config_locked: boolean
  created_at: string
  updated_at: string
}

/** Teacher-facing publication row (08 §10.2). */
export interface TrainingPublicationView {
  publication_id: string
  template_id: string
  template_name: string
  training_level: TrainingLevel
  topic: string
  source_type: TrainingTemplateSourceType
  course_id: string
  course_name: string
  status: TrainingPublicationStatus
  published_at: string | null
  unpublished_at: string | null
  unpublish_reason: TrainingUnpublishReason | null
  max_duration_seconds: number
  virtual_student_count: number
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
  publication_id: string | null
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
  mime: string
  size: number
  char_count: number
  preview: string
  status: string
  usage: 'free_session' | 'template'
  template_id: string | null
  expires_at: string
}

export interface TrainingRecordView {
  record_id: string
  session_id: string
  template_id: string | null
  publication_id: string | null
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

/** M3 task-editor prefill (08 §10.8); teacher-only and reserved for the template owner. */
export interface TaskTemplateConfigView {
  template_id: string
  name: string
  training_level: TrainingLevel
  topic: string
  textbook_version: string | null
  duration: TrainingDuration
  weights: Record<string, number>
  virtual_students: VirtualStudentConfig[]
  reference_material: TrainingMaterialRef | null
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

export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}
