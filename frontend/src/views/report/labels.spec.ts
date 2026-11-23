import { describe, expect, it } from 'vitest'
import {
  STATUS_LABELS,
  dimensionLabel,
  formatDateTime,
  formatTimestamp,
  reasonLabel,
  subItemLabel,
} from './labels'

describe('report labels', () => {
  it('maps every terminal and waiting status to Chinese text', () => {
    expect(STATUS_LABELS.pending).toBe('排队中')
    expect(STATUS_LABELS.running).toBe('评测中')
    expect(STATUS_LABELS.completed).toBe('已完成')
    expect(STATUS_LABELS.partial).toBe('部分完成')
    expect(STATUS_LABELS.failed).toBe('评测失败')
  })

  it('falls back to wire values for unknown dimensions, reasons and sub-items', () => {
    expect(dimensionLabel('content')).toBe('内容')
    expect(dimensionLabel('unknown')).toBe('unknown')
    expect(reasonLabel('AUDIO_MISSING')).toContain('音频缺失')
    expect(reasonLabel('FUTURE_REASON')).toBe('FUTURE_REASON')
    expect(subItemLabel('speech_rate')).toBe('语速')
    expect(subItemLabel('new_sub_item')).toBe('new_sub_item')
  })

  it('formats issue timestamps as mm:ss and rejects missing values', () => {
    expect(formatTimestamp(0)).toBe('00:00')
    expect(formatTimestamp(65_000)).toBe('01:05')
    expect(formatTimestamp(3_599_000)).toBe('59:59')
    expect(formatTimestamp(null)).toBe('—')
    expect(formatTimestamp(-1)).toBe('—')
    expect(formatTimestamp(Number.NaN)).toBe('—')
  })

  it('formats ISO timestamps and passes through invalid input', () => {
    expect(formatDateTime(null)).toBe('—')
    expect(formatDateTime('not-a-date')).toBe('not-a-date')
    expect(formatDateTime('2026-11-23T08:00:00Z')).not.toBe('—')
  })
})
