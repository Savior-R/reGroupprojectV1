import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { DEV_IDENTITY_STORAGE_KEY, useAuthStore } from './auth'

const SCHOOL_ID = '00000000-0000-0000-0000-000000000001'
const TEACHER_ID = '00000000-0000-0000-0000-000000000010'
const STUDENT_ID = '00000000-0000-0000-0000-000000000020'

describe('authStore', () => {
  beforeEach(() => {
    sessionStorage.clear()
    window.history.replaceState({}, '', '/')
    setActivePinia(createPinia())
  })

  afterEach(() => {
    sessionStorage.clear()
    window.history.replaceState({}, '', '/')
  })

  it('defaults to the student identity', () => {
    const auth = useAuthStore()

    expect(auth.accountId).toBe(STUDENT_ID)
    expect(auth.schoolId).toBe(SCHOOL_ID)
    expect(auth.accountType).toBe('STUDENT')
    expect(auth.role).toBe('student')
  })

  it('can switch to the teacher identity and persists it', () => {
    const auth = useAuthStore()
    auth.setDevIdentity('teacher')

    expect(auth.accountId).toBe(TEACHER_ID)
    expect(auth.schoolId).toBe(SCHOOL_ID)
    expect(auth.accountType).toBe('TEACHER')
    expect(auth.role).toBe('teacher')
    expect(sessionStorage.getItem(DEV_IDENTITY_STORAGE_KEY)).toContain('teacher')

    setActivePinia(createPinia())
    const reloaded = useAuthStore()
    expect(reloaded.accountId).toBe(TEACHER_ID)
    expect(reloaded.accountType).toBe('TEACHER')
  })

  it('honors ?dev_role=teacher on first load', () => {
    window.history.replaceState({}, '', '/?dev_role=teacher')

    const auth = useAuthStore()

    expect(auth.accountId).toBe(TEACHER_ID)
    expect(auth.accountType).toBe('TEACHER')
  })
})