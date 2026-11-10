import type { FinalStatus, GradingRow, TaskMode } from './api'

export const DIMENSION_LABELS: Record<string, string> = {
  content: '教学内容',
  expression: '教学表达',
  interaction: '课堂互动与控场',
  board: '板书/PPT',
  time: '时间管理',
}

export const MODE_LABELS: Record<TaskMode, string> = {
  free: '自由试讲',
  scenario: '情景试讲',
  interview: '结构化面试',
}

export const FINAL_STATUS_LABELS: Record<string, string> = {
  completed: '已完成',
  pending_ai: '待 AI',
  pending_teacher: '待教师',
  ai_partial: 'AI 不完整',
  ai_failed: 'AI 失败',
  not_submitted: '未提交',
  in_progress: '进行中',
}

export const STUDENT_STATUS_LABELS: Record<string, string> = {
  active: '在读',
  left: '已退课',
  removed: '已移出',
}

export function dimensionLabel(key: string): string {
  return DIMENSION_LABELS[key] ?? key
}

export function modeLabel(mode: TaskMode | string): string {
  return MODE_LABELS[mode as TaskMode] ?? mode
}

export function statusLabel(status: string | null): string {
  return status ? (FINAL_STATUS_LABELS[status] ?? status) : '—'
}

export function statusTagType(
  status: string | null,
): 'success' | 'warning' | 'danger' | 'info' {
  switch (status) {
    case 'completed':
      return 'success'
    case 'pending_ai':
    case 'pending_teacher':
      return 'warning'
    case 'ai_partial':
    case 'ai_failed':
      return 'danger'
    default:
      return 'info'
  }
}

export function formatScore(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return '—'
  }
  return round1(Number(value)).toFixed(1)
}

export function round1(value: number): number {
  return Math.round((value + Number.EPSILON) * 10) / 10
}

export function formatTime(value: string | null | undefined): string {
  if (!value) {
    return '—'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  return date.toLocaleString('zh-CN', { hour12: false })
}

export function classOptions(
  rows: GradingRow[],
): Array<{ value: string; label: string }> {
  const map = new Map<string, string>()
  for (const row of rows) {
    const key = row.class_id_snapshot ?? ''
    if (!map.has(key)) {
      map.set(key, row.class_name || '未分班')
    }
  }
  return [...map.entries()]
    .filter(([value]) => value !== '')
    .map(([value, label]) => ({ value, label }))
}

export function effectiveStatus(row: GradingRow): string {
  if (row.submission_id === null) {
    return row.session_status === 'preparing' || row.session_status === 'in_progress'
      ? 'in_progress'
      : 'not_submitted'
  }
  return row.final?.status ?? 'pending_ai'
}

export function isFinalStatus(value: string): value is FinalStatus {
  return [
    'pending_ai',
    'pending_teacher',
    'completed',
    'ai_partial',
    'ai_failed',
  ].includes(value)
}
