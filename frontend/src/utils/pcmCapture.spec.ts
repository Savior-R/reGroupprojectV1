import { describe, expect, it } from 'vitest'
import { toPcm16 } from './pcmCapture'

describe('toPcm16', () => {
  it('converts floats to little-endian Int16 at 16 kHz', () => {
    const buffer = toPcm16(new Float32Array([0, 0.5, -0.5, 1]), 16_000)
    const view = new DataView(buffer)
    expect(view.byteLength).toBe(8)
    expect(view.getInt16(0, true)).toBe(0)
    expect(view.getInt16(2, true)).toBe(16_384)
    expect(view.getInt16(4, true)).toBe(-16_384)
    expect(view.getInt16(6, true)).toBe(32_767)
  })

  it('averages groups when downsampling 48 kHz to 16 kHz', () => {
    const input = new Float32Array([0.3, 0.3, 0.3, -0.3, -0.3, -0.3])
    const buffer = toPcm16(input, 48_000)
    const view = new DataView(buffer)
    expect(view.byteLength).toBe(4)
    expect(view.getInt16(0, true)).toBe(Math.round(0.3 * 32_767))
    expect(view.getInt16(2, true)).toBe(Math.round(-0.3 * 32_767))
  })

  it('returns an empty buffer for an empty block', () => {
    expect(toPcm16(new Float32Array(), 48_000).byteLength).toBe(0)
  })
})
