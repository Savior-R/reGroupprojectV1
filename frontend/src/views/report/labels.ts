import type { DimensionName, EvaluationMode, EvaluationStatus, Severity } from './types'

export const STATUS_LABELS: Record<EvaluationStatus, string> = {
  pending: '排队中',
  running: '评测中',
  completed: '已完成',
  partial: '部分完成',
  failed: '评测失败',
}

export const STATUS_TYPES: Record<EvaluationStatus, 'success' | 'warning' | 'danger' | 'info'> = {
  pending: 'info',
  running: 'info',
  completed: 'success',
  partial: 'warning',
  failed: 'danger',
}

export const MODE_LABELS: Record<EvaluationMode, string> = {
  free: '自由试讲',
  scenario: '情景试讲',
  interview: '结构化面试',
}

export const DIMENSION_LABELS: Record<DimensionName, string> = {
  content: '内容',
  expression: '表达',
  interaction: '互动',
  board: '板书',
  time: '时间',
}

export const SUB_ITEM_LABELS: Record<string, string> = {
  accuracy: '准确性',
  logic: '逻辑性',
  key_points: '要点覆盖',
  terminology: '术语',
  completeness: '完整性',
  speech_rate: '语速',
  pause: '停顿',
  filler: '口头禅',
  volume: '音量',
  fluency: '流畅度',
  question_design: '提问设计',
  feedback: '反馈',
  discipline: '纪律',
  rhythm: '节奏',
  structure: '结构',
  consistency: '一致性',
  usage: '使用',
  phases: '阶段划分',
  allocation: '时间分配',
  pacing: '课堂节奏',
  overtime: '超时',
}

export const SEVERITY_LABELS: Record<Severity, string> = {
  high: '高',
  medium: '中',
  low: '低',
}

export const SEVERITY_TYPES: Record<Severity, 'danger' | 'warning' | 'info'> = {
  high: 'danger',
  medium: 'warning',
  low: 'info',
}

export const REASON_LABELS: Record<string, string> = {
  TRANSCRIPT_MISSING: '转写文本缺失，内容 / 互动维度不可用',
  AUDIO_MISSING: '音频缺失，表达维度不可用',
  FRAMES_MISSING: '屏幕帧 / OCR 缺失，板书维度不可用',
  REFERENCE_MISSING: '未提供参考材料，准确性按可见内容评估',
  EMPTY_EVENTS: '没有课堂事件，互动结论置信度降低',
  OCR_LOW_CONFIDENCE: 'OCR 置信度偏低',
  ZERO_WEIGHT_DIMENSION_MISSING: '零权重维度缺失，仅提示且不影响总分',
  DIMENSION_FAILED: '部分维度评测失败',
  DIMENSION_TIMEOUT: '部分维度评测超时',
  INVALID_MODEL_OUTPUT: '模型输出不合规，相关维度已跳过',
  INPUT_FETCH_FAILED: '评测输入拉取失败',
  AI_TIMEOUT: 'AI 评测超时',
  AI_SERVICE_UNAVAILABLE: 'AI 服务暂不可用',
  AI_OUTPUT_INVALID: 'AI 输出校验失败',
  AI_INTERNAL_ERROR: '评测内部错误',
}

export function dimensionLabel(dimension: string): string {
  return DIMENSION_LABELS[dimension as DimensionName] ?? dimension
}

export function subItemLabel(key: string): string {
  return SUB_ITEM_LABELS[key] ?? key
}

export function reasonLabel(code: string): string {
  return REASON_LABELS[code] ?? code
}

export function formatTimestamp(ms: number | null): string {
  if (ms === null || !Number.isFinite(ms) || ms < 0) {
    return '—'
  }
  const totalSeconds = Math.round(ms / 1000)
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
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
