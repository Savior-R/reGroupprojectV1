import type {
  TrainingLevel,
  TrainingOwnerRole,
  TrainingTemplateSourceType,
  TrainingTemplateStatus,
  TrainingUnpublishReason,
} from './types'

export const LEVEL_LABELS: Record<TrainingLevel, string> = {
  progressive: '进阶',
  mock_exam: '模拟考核',
}

export const DIMENSION_LABELS: Record<string, string> = {
  content: '教学内容',
  expression: '表达呈现',
  interaction: '互动组织',
  board: '板书设计',
  time: '时间控制',
}

export const TEMPLATE_SOURCE_LABELS: Record<TrainingTemplateSourceType, string> = {
  manual: '自建',
  task: '任务生成',
  template: '复制',
}

export const TEMPLATE_STATUS_LABELS: Record<TrainingTemplateStatus, string> = {
  active: '使用中',
  archived: '已删除',
}

export const OWNER_ROLE_LABELS: Record<TrainingOwnerRole, string> = {
  teacher: '教师模板',
  student: '学生模板',
}

export const UNPUBLISH_REASON_LABELS: Record<TrainingUnpublishReason, string> = {
  manual: '手动下架',
  course_archived: '目标课程已归档',
  teacher_removed: '已移出课程老师组',
  template_deleted: '模板已删除',
}

export function levelLabel(level: TrainingLevel): string {
  return LEVEL_LABELS[level] ?? level
}

export function dimensionLabel(dimension: string): string {
  return DIMENSION_LABELS[dimension] ?? dimension
}

export function sourceLabel(source: TrainingTemplateSourceType): string {
  return TEMPLATE_SOURCE_LABELS[source] ?? source
}

export function templateStatusLabel(status: TrainingTemplateStatus): string {
  return TEMPLATE_STATUS_LABELS[status] ?? status
}

export function ownerRoleLabel(role: TrainingOwnerRole): string {
  return OWNER_ROLE_LABELS[role] ?? role
}

export function unpublishReasonLabel(reason: string | null): string {
  if (!reason) {
    return '—'
  }
  return UNPUBLISH_REASON_LABELS[reason as TrainingUnpublishReason] ?? reason
}

export function formatDuration(ms: number): string {
  const totalSeconds = Math.max(0, Math.round(ms / 1000))
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  if (minutes === 0) {
    return `${seconds} 秒`
  }
  return seconds === 0 ? `${minutes} 分钟` : `${minutes} 分 ${seconds} 秒`
}

export function formatSeconds(seconds: number): string {
  return formatDuration(seconds * 1000)
}

export function formatDateTime(value: string | null): string {
  if (!value) {
    return '—'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  return date.toLocaleString('zh-CN', { hour12: false })
}

export function evaluationLabel(status: string): string {
  switch (status) {
    case 'pending':
    case 'queued':
      return '等待评测'
    case 'running':
      return '评测中'
    case 'completed':
      return '评测完成'
    case 'partial':
      return '部分完成'
    case 'failed':
      return '评测失败'
    default:
      return status
  }
}
