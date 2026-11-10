import type { TrainingLevel } from './types'

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

export function levelLabel(level: TrainingLevel): string {
  return LEVEL_LABELS[level] ?? level
}

export function dimensionLabel(dimension: string): string {
  return DIMENSION_LABELS[dimension] ?? dimension
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
