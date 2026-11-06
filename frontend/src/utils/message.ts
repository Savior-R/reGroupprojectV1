/** Return an Error's message when available, otherwise the fallback copy. */
export function messageOf(value: unknown, fallback: string): string {
  return value instanceof Error && value.message ? value.message : fallback
}