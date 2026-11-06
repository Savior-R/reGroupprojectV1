/**
 * Pure multi-stream replay synchronization helpers (05B §7.2, 09 §10.4).
 *
 * The replay manifest records a session-relative offset per stream. A stream's
 * own media timeline starts at `offset_ms`, so converting a session position to
 * a media position is `(session_ms - offset_ms) / 1000` seconds. These helpers
 * stay framework-free so the drift/seek rules can be unit-tested without a
 * browser media stack.
 */

/** Anything that can be seeked like an `<audio>` / `<video>` element. */
export interface ReplaySyncPlayer {
  /** Media-local playback position in seconds. */
  currentTime: number
  /** Stream offset relative to the session start, in milliseconds. */
  offsetMs?: number
  /** Media duration in seconds; non-finite values are treated as unknown. */
  duration?: number
}

/** Convert a session position to a stream-local media position (seconds). */
export function targetTime(sessionMs: number, offsetMs: number): number {
  return (sessionMs - offsetMs) / 1000
}

/**
 * Clamp a media position to `[0, duration]`. A non-finite or non-positive
 * duration means the metadata has not loaded yet, so only the lower bound is
 * enforced.
 */
export function clampSeek(time: number, duration: number): number {
  if (!Number.isFinite(time)) {
    return 0
  }
  const max = Number.isFinite(duration) && duration > 0 ? duration : Math.max(0, time)
  return Math.min(Math.max(time, 0), max)
}

/**
 * True when the slave stream drifted more than `thresholdMs` from the master.
 * Both inputs are media-local times in seconds; the comparison is strictly
 * greater than the threshold, matching the "exceeds 200 ms" rule.
 */
export function needsCorrection(masterTime: number, slaveTime: number, thresholdMs = 200): boolean {
  return Math.abs(masterTime - slaveTime) * 1000 > thresholdMs
}

/**
 * Seek every stream to the same session position, applying each stream's
 * offset and clamping to its own duration.
 */
export function seekAll(players: readonly ReplaySyncPlayer[], sessionMs: number): void {
  for (const player of players) {
    const duration = player.duration ?? Number.POSITIVE_INFINITY
    player.currentTime = clampSeek(targetTime(sessionMs, player.offsetMs ?? 0), duration)
  }
}