import axios from 'axios'
import type { AxiosInstance } from 'axios'

/**
 * M1 认证 API 客户端（02 §4.1）。
 *
 * 独立于 `api/client.ts`：后者仍注入 M3 的 dev 身份头，属于共享层（Score/QA）。
 * 本模块只带 `Authorization: Bearer <jwt>`，M1 页面与 `views/auth`、`views/admin` 使用。
 * 全站切换到 JWT 时，由 Score/QA 在 `client.ts` 复用 {@link getToken}。
 */

export const AUTH_TOKEN_KEY = 'm1.auth.token'

export function getToken(): string | null {
  if (typeof window === 'undefined') {
    return null
  }
  return window.localStorage.getItem(AUTH_TOKEN_KEY)
}

export function setToken(token: string | null): void {
  if (typeof window === 'undefined') {
    return
  }
  if (token) {
    window.localStorage.setItem(AUTH_TOKEN_KEY, token)
  } else {
    window.localStorage.removeItem(AUTH_TOKEN_KEY)
  }
}

export const authClient: AxiosInstance = axios.create({ baseURL: '/api/v1' })

authClient.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`)
  }
  return config
})

// --------------------------------------------------------------------- 类型

export type AccountType = 'student' | 'teacher' | 'admin'

export interface MajorView {
  id: string
  name: string
  active?: boolean
}

export interface StudentView {
  class_id: string
  grade: string
  major_name: string | null
  class_name: string
}

export interface TeacherView {
  majors: Array<{ id: string; name: string }>
}

export interface AccountView {
  account_id: string
  username: string
  account_type: AccountType
  school_id: string | null
  school_name: string | null
  email: string | null
  email_verified: boolean
  student: StudentView | null
  teacher: TeacherView | null
}

export interface AuthResult {
  token: string
  expires_at: string
  me: AccountView
}

export interface RegisterPayload {
  account_type: AccountType
  username: string
  password: string
  email?: string | null
  school_id?: string | null
  class_id?: string | null
  major_ids?: string[]
}

export interface QrView {
  state: string
  authorize_url: string
  expires_at: string
}

export type OAuthStatus =
  | 'pending'
  | 'scanned'
  | 'authorized'
  | 'needs_registration'
  | 'expired'
  | 'failed'

export interface OAuthStatusView {
  status: OAuthStatus
  ticket?: string | null
}

export interface SchoolView {
  school_id: string
  name: string
}

export interface ClassView {
  id: string
  major_id: string
  major_name: string | null
  grade: string
  name: string
  active: boolean
}

// --------------------------------------------------------------------- 接口

export async function register(payload: RegisterPayload): Promise<AuthResult> {
  const { data } = await authClient.post<AuthResult>('/auth/register', payload)
  return data
}

export async function login(username: string, password: string): Promise<AuthResult> {
  const { data } = await authClient.post<AuthResult>('/auth/login', { username, password })
  return data
}

export async function sendEmailCode(email: string): Promise<void> {
  await authClient.post('/auth/email/code', { email, purpose: 'login' })
}

export async function loginWithEmail(email: string, code: string): Promise<AuthResult> {
  const { data } = await authClient.post<AuthResult>('/auth/login/email', { email, code })
  return data
}

export async function me(): Promise<AccountView> {
  const { data } = await authClient.get<AccountView>('/auth/me')
  return data
}

export async function createWechatQr(): Promise<QrView> {
  const { data } = await authClient.get<QrView>('/auth/oauth/wechat/qr')
  return data
}

export async function wechatStatus(state: string): Promise<OAuthStatusView> {
  const { data } = await authClient.get<OAuthStatusView>('/auth/oauth/wechat/status', {
    params: { state },
  })
  return data
}

export async function exchangeWechatTicket(ticket: string): Promise<AuthResult> {
  const { data } = await authClient.post<AuthResult>('/auth/oauth/wechat/exchange', { ticket })
  return data
}

export async function registerWithWechat(
  ticket: string,
  payload: RegisterPayload,
): Promise<AuthResult> {
  const { data } = await authClient.post<AuthResult>('/auth/oauth/wechat/register', {
    ticket,
    ...payload,
  })
  return data
}

export async function sendBindCode(email: string): Promise<void> {
  await authClient.post('/auth/email/bind/code', { email, purpose: 'bind' })
}

export async function bindEmail(
  email: string,
  code: string,
  currentPassword: string,
): Promise<void> {
  await authClient.post('/auth/email/bind', {
    email,
    code,
    current_password: currentPassword,
  })
}

export async function unbindEmail(currentPassword: string): Promise<void> {
  await authClient.delete('/auth/email', { data: { current_password: currentPassword } })
}

export async function bindWechat(ticket: string, currentPassword: string): Promise<void> {
  await authClient.post('/auth/oauth/wechat/bind', {
    ticket,
    current_password: currentPassword,
  })
}

export async function unbindWechat(currentPassword: string): Promise<void> {
  await authClient.delete('/auth/oauth/wechat', {
    data: { current_password: currentPassword },
  })
}

// --------------------------------------------------------------- 公开选项

export async function listSchools(): Promise<SchoolView[]> {
  const { data } = await authClient.get<SchoolView[]>('/public/schools')
  return data
}

export async function listPublicMajors(schoolId: string): Promise<MajorView[]> {
  const { data } = await authClient.get<MajorView[]>(`/public/schools/${schoolId}/majors`)
  return data
}

export async function listGrades(schoolId: string): Promise<string[]> {
  const { data } = await authClient.get<string[]>(`/public/schools/${schoolId}/grades`)
  return data
}

export async function listPublicClasses(
  schoolId: string,
  majorId?: string,
  grade?: string,
): Promise<ClassView[]> {
  const { data } = await authClient.get<ClassView[]>('/public/classes', {
    params: { schoolId, majorId, grade },
  })
  return data
}

// --------------------------------------------------------------- 管理端

export interface SchoolCreatedResult {
  school: SchoolView
  token: string
  expires_at: string
}

export async function createSchool(name: string): Promise<SchoolCreatedResult> {
  const { data } = await authClient.post<SchoolCreatedResult>('/admin/schools', { name })
  return data
}

export async function currentSchool(): Promise<SchoolView> {
  const { data } = await authClient.get<SchoolView>('/admin/school')
  return data
}

export async function listAdminMajors(): Promise<MajorView[]> {
  const { data } = await authClient.get<MajorView[]>('/admin/majors')
  return data
}

export async function createMajor(name: string): Promise<MajorView> {
  const { data } = await authClient.post<MajorView>('/admin/majors', { name })
  return data
}

export async function updateMajor(
  majorId: string,
  patch: { name?: string; active?: boolean },
): Promise<MajorView> {
  const { data } = await authClient.patch<MajorView>(`/admin/majors/${majorId}`, patch)
  return data
}

export async function listAdminClasses(
  majorId?: string,
  grade?: string,
): Promise<ClassView[]> {
  const { data } = await authClient.get<ClassView[]>('/admin/classes', {
    params: { majorId, grade },
  })
  return data
}

export async function createClass(
  majorId: string,
  grade: string,
  name: string,
): Promise<ClassView> {
  const { data } = await authClient.post<ClassView>('/admin/classes', {
    major_id: majorId,
    grade,
    name,
  })
  return data
}

export interface TeacherRow {
  account_id: string
  username: string
  school_id: string | null
  majors: Array<{ id: string; name: string }>
}

export async function listTeachers(): Promise<TeacherRow[]> {
  const { data } = await authClient.get<TeacherRow[]>('/admin/teachers')
  return data
}

export async function updateTeacherMajors(
  accountId: string,
  majorIds: string[],
): Promise<TeacherRow> {
  const { data } = await authClient.put<TeacherRow>(`/admin/teachers/${accountId}/majors`, {
    major_ids: majorIds,
  })
  return data
}

// ------------------------------------------------------------------ 组织导入

export interface ImportErrorRow {
  row: number
  column: string
  message: string
}

export interface ImportResult {
  total: number
  created: number
  updated: number
  errors: ImportErrorRow[]
}

async function uploadOrgFile(path: string, file: File): Promise<ImportResult> {
  const form = new FormData()
  form.append('file', file)
  const { data } = await authClient.post<ImportResult>(path, form)
  return data
}

/** 导入专业：CSV / XLSX，自然键幂等，任一行失败整批不写入（FR-M1-04）。 */
export function importMajors(file: File): Promise<ImportResult> {
  return uploadOrgFile('/admin/majors/import', file)
}

/** 导入班级：`major_name` 必须是本校已存在且启用的专业。 */
export function importClasses(file: File): Promise<ImportResult> {
  return uploadOrgFile('/admin/classes/import', file)
}

/** 下载导入模板。需管理员 token，故走 axios 取 Blob 而非直链。 */
export async function downloadImportTemplate(type: 'major' | 'class'): Promise<void> {
  const { data } = await authClient.get<Blob>(`/admin/imports/templates/${type}`, {
    responseType: 'blob',
  })
  const url = URL.createObjectURL(data)
  const link = document.createElement('a')
  link.href = url
  link.download = `${type}-template.csv`
  link.click()
  URL.revokeObjectURL(url)
}

/** 取后端 `details.errors`，供导入失败明细表展示。 */
export function importErrorRows(error: unknown): ImportErrorRow[] {
  const details = (
    error as { response?: { data?: { details?: { errors?: ImportErrorRow[] } } } }
  ).response?.data?.details
  return details?.errors ?? []
}

/** 取后端统一错误文案；取不到时退回 fallback。 */
export function apiMessage(error: unknown, fallback: string): string {
  const data = (error as { response?: { data?: { message?: string } } }).response?.data
  return data?.message ?? fallback
}
