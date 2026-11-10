import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type { AccountType, DevRole } from '../types/api'

/**
 * Dev-only identity store (09 §8.1). M1 replaces this with JWT auth.
 *
 * The selected role is persisted in sessionStorage so a page refresh keeps the
 * same dev identity. `?dev_role=teacher` is honoured on first load when no role
 * has been stored yet.
 */

export const DEV_IDENTITY_STORAGE_KEY = 'm3.dev-identity'

export const DEV_SCHOOL_ID = '00000000-0000-0000-0000-000000000001'
export const DEV_TEACHER_ID = '00000000-0000-0000-0000-000000000010'
export const DEV_STUDENT_ID = '00000000-0000-0000-0000-000000000020'

interface DevIdentity {
  accountId: string
  schoolId: string
  accountType: AccountType
}

export const DEV_IDENTITIES: Record<DevRole, DevIdentity> = {
  student: {
    accountId: DEV_STUDENT_ID,
    schoolId: DEV_SCHOOL_ID,
    accountType: 'STUDENT',
  },
  teacher: {
    accountId: DEV_TEACHER_ID,
    schoolId: DEV_SCHOOL_ID,
    accountType: 'TEACHER',
  },
}

function isDevRole(value: unknown): value is DevRole {
  return value === 'student' || value === 'teacher'
}

function readStoredRole(): DevRole | null {
  if (typeof window === 'undefined') {
    return null
  }
  try {
    const raw = window.sessionStorage.getItem(DEV_IDENTITY_STORAGE_KEY)
    if (!raw) {
      return null
    }
    const parsed: unknown = JSON.parse(raw)
    if (typeof parsed === 'string') {
      return isDevRole(parsed) ? parsed : null
    }
    if (parsed !== null && typeof parsed === 'object' && 'role' in parsed) {
      const role = (parsed as { role: unknown }).role
      return isDevRole(role) ? role : null
    }
    return null
  } catch {
    return null
  }
}

function readQueryRole(): DevRole | null {
  if (typeof window === 'undefined') {
    return null
  }
  try {
    const value = new URLSearchParams(window.location.search).get('dev_role')
    return isDevRole(value) ? value : null
  } catch {
    return null
  }
}

function persistRole(role: DevRole): void {
  if (typeof window === 'undefined') {
    return
  }
  try {
    window.sessionStorage.setItem(DEV_IDENTITY_STORAGE_KEY, JSON.stringify({ role }))
  } catch {
    // Dev-only convenience; a blocked sessionStorage must not break the app.
  }
}

function resolveInitialRole(): DevRole {
  // An explicit ?dev_role= override wins so direct teacher/student demo URLs
  // work even after a different role was persisted earlier in the session.
  return readQueryRole() ?? readStoredRole() ?? 'student'
}

export const useAuthStore = defineStore('auth', () => {
  const initialRole = resolveInitialRole()
  const initialIdentity = DEV_IDENTITIES[initialRole]

  const role = ref<DevRole>(initialRole)
  const accountId = ref(initialIdentity.accountId)
  const schoolId = ref(initialIdentity.schoolId)
  const accountType = ref<AccountType>(initialIdentity.accountType)

  const isTeacher = computed(() => accountType.value === 'TEACHER')
  const isStudent = computed(() => accountType.value === 'STUDENT')

  persistRole(initialRole)

  function setDevIdentity(next: DevRole): void {
    const preset = DEV_IDENTITIES[next]
    role.value = next
    accountId.value = preset.accountId
    schoolId.value = preset.schoolId
    accountType.value = preset.accountType
    persistRole(next)
  }

  return {
    role,
    accountId,
    schoolId,
    accountType,
    isTeacher,
    isStudent,
    setDevIdentity,
  }
})