/**
 * Client-side opaque id for local editor rows (virtual students, question keys).
 *
 * `crypto.randomUUID` is only available in secure contexts; the manual acceptance
 * server is often opened via a LAN IP over plain HTTP, so keep a fallback.
 */
export function clientId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `id-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}
