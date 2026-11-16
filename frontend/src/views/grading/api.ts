import { apiClient } from '../../api/client'
import { useAuthStore } from '../../stores/auth'

/**
 * M7 teacher-evaluation API client (02 §4.7 / 07 §11).
 *
 * The module lives under the M7-owned grading view folder so the shared API
 * folder keeps its existing M3 scope. All payloads use the backend snake_case
 * contract.
 *
 * The demo seed (V900) uses different account IDs than the M3 dev identity
 * presets. Until M1 auth lands, M7 requests carry the matching demo identities
 * so the teacher/student pages work against the demo database.
 */

export const DEMO_SCHOOL_ID = '10000000-0000-0000-0000-000000000001'
export const DEMO_TEACHER_ID = '20000000-0000-0000-0000-000000000011'
export const DEMO_STUDENT_ID = '20000000-0000-0000-0000-000000000101'

function demoHeaders(): Record<string, string> {
  const auth = useAuthStore()
  const teacher = auth.role === 'teacher'
  return {
    'X-Account-Id': teacher ? DEMO_TEACHER_ID : DEMO_STUDENT_ID,
    'X-School-Id': DEMO_SCHOOL_ID,
    'X-Account-Type': teacher ? 'TEACHER' : 'STUDENT',
  }
}

export type ScoringMode = 'dimension' | 'total'
export type TaskMode = 'free' | 'scenario' | 'interview'
export type FinalStatus =
  | 'pending_ai'
  | 'pending_teacher'
  | 'completed'
  | 'ai_partial'
  | 'ai_failed'

export type DimensionScores = Record<string, number>

export interface GradingConfigView {
  task_id: string
  course_id: string
  mode: TaskMode
  dimensions: string[]
  rubric_weights: Record<string, number>
  ai_weight: number
  teacher_weight: number
  scoring_mode: ScoringMode
  config_version: number
  locked_at: string | null
  can_edit: boolean
  has_scores: boolean
}

export interface AiScoreView {
  evaluation_id: string
  status: string
  total_score: number | null
  dimension_scores: DimensionScores
}

export interface TeacherScoreRow {
  teacher_score_id: string
  scoring_mode: ScoringMode
  dimension_scores: DimensionScores
  teacher_total: number
  comment: string | null
  version: number
  graded_by: string
  graded_at: string
}

export interface FinalScoreRow {
  status: FinalStatus
  ai_total: number | null
  teacher_total: number | null
  final_total: number | null
  final_dimensions: DimensionScores
  error_code: string | null
  computed_at: string | null
}

export interface GradingRow {
  submission_id: string | null
  student_account_id: string
  student_name: string
  class_id: string | null
  class_name: string
  class_id_snapshot: string | null
  student_status: 'active' | 'left' | 'removed'
  session_status: string
  submitted_at: string | null
  ai: AiScoreView | null
  teacher_score: TeacherScoreRow | null
  final: FinalScoreRow | null
}

export interface SubmissionSummary {
  assigned: number
  submitted: number
  completed: number
  pending_ai: number
  pending_teacher: number
  ai_partial: number
  ai_failed: number
  not_submitted: number
  in_progress: number
}

export interface GradingResponse {
  task_id: string
  course_id: string
  mode: TaskMode
  scoring_mode: ScoringMode
  rubric_weights: Record<string, number>
  summary: SubmissionSummary
  items: GradingRow[]
  total: number
  page: number
  size: number
}

export interface GradingListParams {
  class_id?: string
  status?: string
  keyword?: string
  page?: number
  size?: number
}

export interface UpdateGradingConfigBody {
  ai_weight: number
  teacher_weight: number
  scoring_mode: ScoringMode
  config_version: number
}

export interface SaveTeacherScoreBody {
  scoring_mode: ScoringMode
  dimension_scores?: DimensionScores
  teacher_total?: number
  comment?: string
  version: number
}

export interface BatchScoreItem extends SaveTeacherScoreBody {
  submission_id: string
}

export interface BatchRowResult {
  submission_id: string
  ok: boolean
  teacher_score: TeacherScoreRow | null
  final: FinalScoreRow | null
  error_code: string | null
  message: string | null
}

export interface BatchSaveResponse {
  success_count: number
  failure_count: number
  results: BatchRowResult[]
}

export interface TeacherScoreSaveResponse {
  teacher_score: TeacherScoreRow
  final: FinalScoreRow | null
}

export interface ClearScoresResponse {
  deleted_count: number
  unlocked: boolean
  recomputed: number
}

export interface DimensionStat {
  dimension: string
  average: number | null
  sample_count: number
  weak: boolean
}

export interface TaskStatsResponse {
  task_id: string
  course_id: string
  class_id: string | null
  summary: {
    assigned: number
    submitted: number
    not_submitted: number
    completed: number
    pending_ai: number
    pending_teacher: number
    ai_partial: number
    ai_failed: number
    in_progress: number
    average_final: number | null
  }
  dimensions: DimensionStat[]
  weak_dimensions: string[]
  sample_sufficient: boolean
}

export interface LeaderboardItem {
  rank: number
  student_account_id: string
  student_name: string
  class_name: string
  ai_total: number | null
  teacher_total: number | null
  final_total: number | null
}

export interface LeaderboardResponse {
  task_id: string
  class_id: string | null
  items: LeaderboardItem[]
  total: number
  page: number
  size: number
}

export interface CourseStatsResponse {
  course_id: string
  class_id: string | null
  summary: {
    tasks: number
    assigned: number
    submitted: number
    completed: number
    average_final: number | null
  }
  tasks: Array<{
    task_id: string
    topic: string
    mode: TaskMode
    assigned: number
    submitted: number
    completed: number
    average_final: number | null
  }>
}

export interface StudentResultRow {
  submission_id: string
  task_id: string
  task_title: string
  course_name: string
  submitted_at: string
  status: FinalStatus
  final_total: number | null
}

export interface StudentResultList {
  items: StudentResultRow[]
  total: number
  page: number
  size: number
}

export interface StudentResultDetail {
  submission_id: string
  task_id: string
  task_title: string
  course_name: string
  submitted_at: string
  status: FinalStatus
  ai_total: number | null
  teacher_total: number | null
  final_total: number | null
  final_dimensions: DimensionScores
  teacher_comment: string | null
  ai_report_url: string | null
  replay_url: string | null
  error_code: string | null
  error_message: string | null
}

export function getGradingConfig(taskId: string): Promise<GradingConfigView> {
  return apiClient
    .get<GradingConfigView>(`/tasks/${encodeURIComponent(taskId)}/grading-config`, {
      headers: demoHeaders(),
    })
    .then((response) => response.data)
}

export function updateGradingConfig(
  taskId: string,
  body: UpdateGradingConfigBody,
): Promise<GradingConfigView> {
  return apiClient
    .put<GradingConfigView>(
      `/tasks/${encodeURIComponent(taskId)}/grading-config`,
      body,
      { headers: demoHeaders() },
    )
    .then((response) => response.data)
}

export function clearTeacherScores(taskId: string): Promise<ClearScoresResponse> {
  return apiClient
    .delete<ClearScoresResponse>(`/tasks/${encodeURIComponent(taskId)}/teacher-scores`, {
      data: { confirm: true },
      headers: demoHeaders(),
    })
    .then((response) => response.data)
}

export function listGradingSubmissions(
  taskId: string,
  params: GradingListParams = {},
): Promise<GradingResponse> {
  return apiClient
    .get<GradingResponse>(`/tasks/${encodeURIComponent(taskId)}/grading-submissions`, {
      params,
      headers: demoHeaders(),
    })
    .then((response) => response.data)
}

export function saveTeacherScore(
  submissionId: string,
  body: SaveTeacherScoreBody,
): Promise<TeacherScoreSaveResponse> {
  return apiClient
    .put<TeacherScoreSaveResponse>(
      `/submissions/${encodeURIComponent(submissionId)}/teacher-score`,
      body,
      { headers: demoHeaders() },
    )
    .then((response) => response.data)
}

export function batchSaveTeacherScores(
  taskId: string,
  items: BatchScoreItem[],
): Promise<BatchSaveResponse> {
  return apiClient
    .post<BatchSaveResponse>(
      `/tasks/${encodeURIComponent(taskId)}/teacher-scores/batch`,
      { items },
      { headers: demoHeaders() },
    )
    .then((response) => response.data)
}

export function getTaskStats(taskId: string, classId?: string): Promise<TaskStatsResponse> {
  return apiClient
    .get<TaskStatsResponse>(`/tasks/${encodeURIComponent(taskId)}/stats`, {
      params: classId ? { class_id: classId } : {},
      headers: demoHeaders(),
    })
    .then((response) => response.data)
}

export function getLeaderboard(
  taskId: string,
  options: { classId?: string; page?: number; size?: number } = {},
): Promise<LeaderboardResponse> {
  return apiClient
    .get<LeaderboardResponse>(`/tasks/${encodeURIComponent(taskId)}/leaderboard`, {
      params: {
        class_id: options.classId,
        page: options.page ?? 1,
        size: options.size ?? 20,
      },
      headers: demoHeaders(),
    })
    .then((response) => response.data)
}

export function getCourseStats(
  courseId: string,
  classId?: string,
): Promise<CourseStatsResponse> {
  return apiClient
    .get<CourseStatsResponse>(`/courses/${encodeURIComponent(courseId)}/stats`, {
      params: classId ? { class_id: classId } : {},
      headers: demoHeaders(),
    })
    .then((response) => response.data)
}

export interface CsvDownload {
  blob: Blob
  filename: string
}

export async function downloadTaskCsv(
  taskId: string,
  params: { classId?: string; status?: string; keyword?: string } = {},
): Promise<CsvDownload> {
  const response = await apiClient.get(`/tasks/${encodeURIComponent(taskId)}/export`, {
    params: {
      class_id: params.classId,
      status: params.status,
      keyword: params.keyword,
    },
    responseType: 'blob',
    headers: demoHeaders(),
  })
  const disposition = String(response.headers['content-disposition'] ?? '')
  const match = /filename="?([^";]+)"?/.exec(disposition)
  return {
    blob: response.data as Blob,
    filename: match?.[1] ?? `task-${taskId}-scores.csv`,
  }
}

export function listStudentResults(page = 1, size = 20): Promise<StudentResultList> {
  return apiClient
    .get<StudentResultList>('/student/results', {
      params: { page, size },
      headers: demoHeaders(),
    })
    .then((response) => response.data)
}

export function getStudentResult(submissionId: string): Promise<StudentResultDetail> {
  return apiClient
    .get<StudentResultDetail>(`/submissions/${encodeURIComponent(submissionId)}/result`, {
      headers: demoHeaders(),
    })
    .then((response) => response.data)
}
