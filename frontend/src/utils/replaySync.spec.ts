import { describe, expect, it } from 'vitest'
import { clampSeek, needsCorrection, seekAll, targetTime } from './replaySync'
import type { ReplaySyncPlayer } from './replaySync'

describe('replaySync', () => {
  it('applies stream offset', () => {
    expect(targetTime(5000, 120)).toBeCloseTo(4.88)
    expect(targetTime(5000, 0)).toBe(5)
    expect(targetTime(0, 0)).toBe(0)
  })

  it('clamps seek within duration', () => {
    expect(clampSeek(-1.2, 10)).toBe(0)
    expect(clampSeek(12, 10)).toBe(10)
    expect(clampSeek(4.88, 10)).toBeCloseTo(4.88)
    // Unknown duration (metadata not loaded): only the lower bound applies.
    expect(clampSeek(3, Number.POSITIVE_INFINITY)).toBe(3)
    expect(clampSeek(Number.NaN, 10)).toBe(0)
  })

  it('corrects only when drift exceeds 200ms', () => {
    // Exactly 200 ms is not "exceeds 200 ms".
    expect(needsCorrection(10, 10.2)).toBe(false)
    expect(needsCorrection(10, 9.8)).toBe(false)
    expect(needsCorrection(10, 10.201)).toBe(true)
    expect(needsCorrection(10, 9.799)).toBe(true)
    // Custom threshold.
    expect(needsCorrection(10, 10.4, 500)).toBe(false)
    expect(needsCorrection(10, 10.6, 500)).toBe(true)
  })

  it('seeks all streams to an event at_ms', () => {
    const players: ReplaySyncPlayer[] = [
      { currentTime: 0, offsetMs: 0, duration: 60 },
      { currentTime: 0, offsetMs: 120, duration: 60 },
      { currentTime: 0, offsetMs: 250, duration: 60 },
    ]

    seekAll(players, 5000)

    expect(players[0]!.currentTime).toBeCloseTo(5)
    expect(players[1]!.currentTime).toBeCloseTo(4.88)
    expect(players[2]!.currentTime).toBeCloseTo(4.75)

    // An event before a stream's offset clamps that stream to its start.
    seekAll([players[1]!], 100)
    expect(players[1]!.currentTime).toBe(0)

    // And nothing exceeds the stream duration.
    seekAll([players[0]!], 90_000)
    expect(players[0]!.currentTime).toBe(60)
  })
})