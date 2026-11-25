/**
 * E2E 全局种子：通过后端 REST API 构造 M1-T08/09/10 所需账号与组织数据。
 *
 * 幂等：用户名固定，重复注册返回 409 时改为直接登录取 token。
 * 产出写入 `e2e/.state.json`，用例通过 `loadState()` 读取。
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import type { APIRequestContext, Page } from '@playwright/test'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

const API = process.env.E2E_API_URL ?? 'http://localhost:8091'
const PASSWORD = 'Passw0rd!23'

export interface SeededAccount {
  username: string
  password: string
  token: string
  accountId?: string
}

interface E2EState {
  api: string
  adminNoSchool: SeededAccount
  adminWithSchool: SeededAccount
  teacher: SeededAccount
  student: SeededAccount
  school: { id: string; name: string }
  major: { id: string; name: string }
  klass: { id: string; grade: string; name: string }
}

const STATE_FILE = path.join(__dirname, '..', '.state.json')

async function apiPost(request: APIRequestContext, path: string, data: unknown) {
  const response = await request.post(`${API}${path}`, { data })
  return response
}

async function registerOrLogin(request: APIRequestContext, body: Record<string, unknown>, username: string): Promise<SeededAccount> {
  const register = await apiPost(request, '/api/v1/auth/register', body)
  if (register.ok()) {
    const result = (await register.json()) as { token: string; me: { account_id: string } }
    return { username, password: PASSWORD, token: result.token, accountId: result.me.account_id }
  }
  // 409 / 重复：回退到登录（库已有同名账号）
  const login = await apiPost(request, '/api/v1/auth/login', { username, password: PASSWORD })
  if (!login.ok()) {
    throw new Error(`种子账号 ${username} 注册失败且无法登录：${register.status()} / ${login.status()}`)
  }
  const result = (await login.json()) as { token: string; me: { account_id: string } }
  return { username, password: PASSWORD, token: result.token, accountId: result.me.account_id }
}

async function login(request: APIRequestContext, username: string, password: string): Promise<string> {
  const login = await apiPost(request, '/api/v1/auth/login', { username, password })
  if (!login.ok()) throw new Error(`登录失败 ${username}: ${login.status()}`)
  return ((await login.json()) as { token: string }).token
}

/** 登录后把 token 写入 localStorage，供页面以已登录态加载。 */
export async function loginAs(page: Page, account: SeededAccount): Promise<void> {
  const response = await page.request.post(`${API}/api/v1/auth/login`, {
    data: { username: account.username, password: account.password },
  })
  if (!response.ok()) throw new Error(`E2E 登录失败 ${account.username}: ${response.status()}`)
  const { token } = (await response.json()) as { token: string }
  await page.goto('/')
  await page.evaluate(([key, value]) => window.localStorage.setItem(key, value), ['m1.auth.token', token])
}

export async function globalSetup(): Promise<void> {
  const request = await (await import('@playwright/test')).request.newContext({ baseURL: API })
  try {
    const adminNoSchool = await registerOrLogin(request, {
      account_type: 'admin', username: 'E2E未建校管理员', password: PASSWORD,
    }, 'E2E未建校管理员')

    const adminWithSchool = await registerOrLogin(request, {
      account_type: 'admin', username: 'E2E建校管理员', password: PASSWORD,
    }, 'E2E建校管理员')

    // 建校（若已绑定会 409 → 重新登录拿新 token 后再取 /school）
    let adminToken = adminWithSchool.token
    const createSchool = await request.post(`${API}/api/v1/admin/schools`, {
      data: { name: 'E2E测试第一小学' }, headers: { Authorization: `Bearer ${adminToken}` },
    })
    if (createSchool.ok()) {
      const result = (await createSchool.json()) as { school: { school_id: string; name: string }, token: string }
      adminToken = result.token
    }

    // 专业 + 班级（幂等：列表非空即跳过创建）
    const majors = await (await request.get(`${API}/api/v1/admin/majors`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    })).json() as Array<{ id: string; name: string }>
    let major = majors.find((m) => m.name === 'E2E软件工程')
    if (!major) {
      const created = await request.post(`${API}/api/v1/admin/majors`, {
        data: { name: 'E2E软件工程' }, headers: { Authorization: `Bearer ${adminToken}` },
      })
      major = (await created.json()) as { id: string; name: string }
    }

    const classes = await (await request.get(`${API}/api/v1/admin/classes?grade=2024`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    })).json() as Array<{ id: string; grade: string; name: string }>
    let klass = classes.find((c) => c.name === 'E2E一班')
    if (!klass) {
      const created = await request.post(`${API}/api/v1/admin/classes`, {
        data: { major_id: major.id, grade: '2024', name: 'E2E一班' },
        headers: { Authorization: `Bearer ${adminToken}` },
      })
      klass = (await created.json()) as { id: string; grade: string; name: string }
    }

    const teacher = await registerOrLogin(request, {
      account_type: 'teacher', username: 'E2E教师甲', password: PASSWORD,
      school_id: (await schoolId(request, adminToken)), major_ids: [major.id],
    }, 'E2E教师甲')

    const student = await registerOrLogin(request, {
      account_type: 'student', username: 'E2E学生甲', password: PASSWORD,
      school_id: (await schoolId(request, adminToken)), class_id: klass.id,
    }, 'E2E学生甲')

    const state: E2EState = {
      api: API,
      adminNoSchool,
      adminWithSchool: { ...adminWithSchool, token: adminToken },
      teacher,
      student,
      school: { id: await schoolId(request, adminToken), name: 'E2E测试第一小学' },
      major,
      klass,
    }
    fs.writeFileSync(STATE_FILE, JSON.stringify(state, null, 2))
  } finally {
    await request.dispose()
  }
}

async function schoolId(request: APIRequestContext, token: string): Promise<string> {
  const response = await request.get(`${API}/api/v1/admin/school`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  if (!response.ok()) throw new Error(`读取学校失败: ${response.status()}`)
  // SchoolView 序列化为 snake_case：{"school_id": "...", "name": "..."}
  const school = (await response.json()) as { school_id: string }
  return school.school_id
}

export function loadState(): E2EState {
  if (!fs.existsSync(STATE_FILE)) {
    throw new Error('缺少 e2e/.state.json：请先执行 `npx playwright test --global-setup` 或直接运行测试（globalSetup 自动生成）')
  }
  return JSON.parse(fs.readFileSync(STATE_FILE, 'utf-8')) as E2EState
}
