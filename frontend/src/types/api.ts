/**
 * Wire-level API types shared by the M3 pages.
 *
 * Backend payloads are serialized with snake_case field names (02 §1), so the
 * TypeScript shapes below intentionally keep snake_case instead of camelCase.
 */

export type AccountType = 'STUDENT' | 'TEACHER' | 'ADMIN'

/** Dev-only identity presets until M1 auth lands. */
export type DevRole = 'student' | 'teacher'

export interface ApiErrorBody {
  code: string
  message: string
  details?: Record<string, unknown>
  trace_id?: string
}

export interface ApiErrorInit {
  code: string
  message: string
  details?: Record<string, unknown>
  trace_id?: string
  status?: number
}

/**
 * Normalized error thrown by the shared Axios client for every failed request
 * (02 §8.2 / 09 §8.2). Pages can rely on `code`, `message`, `details` and
 * `trace_id` without touching the raw Axios error.
 */
export class ApiError extends Error {
  readonly code: string
  readonly details?: Record<string, unknown>
  readonly trace_id?: string
  readonly status?: number

  constructor(init: ApiErrorInit) {
    super(init.message)
    this.name = 'ApiError'
    this.code = init.code
    this.details = init.details
    this.trace_id = init.trace_id
    this.status = init.status
  }
}

/** Shared paged response shape (backend `PageResult`). */
export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}

/** JSON body with a known item type; used by later API modules. */
export type JsonObject = Record<string, unknown>