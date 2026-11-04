# 师范 AI 试讲台 — 前端实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 MVP 前端:登录、选题、三栏虚拟课堂(录制 + 脚本学生 + 语音)、评测报告(雷达图 + 建议 + 回放)、试讲历史。

**Architecture:** Vue 3 + TS + Vite。请求统一走 `api/` 层的 axios 实例;跨组件状态放 Pinia;组合式函数封装「录制 / 课堂脚本调度 / 评测轮询」三类副作用;纯逻辑抽到 `utils/` 便于单测。

**Tech Stack:** Vue 3.5 · TypeScript 6 · Vite 8 · Vue Router · Pinia · Element Plus · ECharts(vue-echarts)· axios · vitest

**前置:** 后端计划已完成,`http://localhost:8000` 可访问。

**Spec:** `docs/superpowers/specs/2026-09-14-shifan-ai-shijiangtai-design.md`

---

## 文件结构

```
frontend/
├── package.json                  # [改] 补依赖 + test 脚本
├── vite.config.ts                # [改] 别名 / 代理 / Element Plus 自动导入
├── tsconfig.app.json             # [改] paths 别名
├── vitest.config.ts              # [建]
└── src/
    ├── main.ts                   # [改] pinia + router + element-plus
    ├── App.vue                   # [改] router-view
    ├── style.css                 # [改] 全局变量与布局重置
    ├── types/index.ts            # [建] 与后端 DTO 对应的类型
    ├── utils/radar.ts            # [建] buildRadarData(纯函数)
    ├── utils/classroom.ts        # [建] takeDueEvents(纯函数)
    ├── api/client.ts             # [建] axios 实例 + 拦截器
    ├── api/auth.ts               # [建]
    ├── api/lessons.ts            # [建]
    ├── api/attempts.ts           # [建]
    ├── stores/user.ts            # [建]
    ├── router/index.ts           # [建]
    ├── composables/useRecorder.ts
    ├── composables/useClassroomScript.ts
    ├── composables/useAttemptPolling.ts
    ├── components/ClassroomBoard.vue
    ├── components/StudentPanel.vue
    ├── components/RecorderControls.vue
    ├── components/SegmentTimeline.vue
    ├── components/RadarChart.vue
    ├── components/ReportCard.vue
    └── views/
        ├── LoginView.vue
        ├── LessonListView.vue
        ├── ClassroomView.vue
        ├── ReportView.vue
        └── HistoryView.vue
```

---

### Task 1: 前端基座(依赖 / 路由 / 请求层 / 状态)

**Files:**
- Modify: `frontend/package.json`, `frontend/vite.config.ts`, `frontend/tsconfig.app.json`, `frontend/src/main.ts`, `frontend/src/App.vue`
- Create: `frontend/vitest.config.ts`, `frontend/src/types/index.ts`, `frontend/src/api/client.ts`, `frontend/src/stores/user.ts`, `frontend/src/router/index.ts`

- [ ] **Step 1: 装依赖**

```bash
cd frontend
npm install vue-router pinia element-plus @element-plus/icons-vue axios echarts vue-echarts
npm install -D unplugin-auto-import unplugin-vue-components vitest jsdom @vue/test-utils
```

- [ ] **Step 2: `frontend/package.json` 加 test 脚本**

在 `"scripts"` 中加:

```json
    "test": "vitest run"
```

- [ ] **Step 3: 改 `frontend/vite.config.ts`**

```ts
import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [
    vue(),
    AutoImport({ resolvers: [ElementPlusResolver()] }),
    Components({ resolvers: [ElementPlusResolver()] }),
  ],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    proxy: {
      '/api': 'http://localhost:8000',
      '/media': 'http://localhost:8000',
    },
  },
})
```

- [ ] **Step 4: 改 `frontend/tsconfig.app.json` 加别名**

在 `compilerOptions` 中加:

```json
    "baseUrl": ".",
    "paths": { "@/*": ["./src/*"] }
```

- [ ] **Step 5: 建 `frontend/vitest.config.ts`**

```ts
import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.spec.ts'],
  },
})
```

- [ ] **Step 6: 建 `frontend/src/types/index.ts`**

```ts
export type UserRole = 'student' | 'teacher'

export interface UserOut {
  id: number
  username: string
  display_name: string
  role: UserRole
  major: string | null
  grade: string | null
}

export interface TokenResponse {
  access_token: string
  token_type: string
  user: UserOut
}

export interface Lesson {
  id: number
  title: string
  subject: string
  stage: string
  textbook: string
  content: string
  key_points: string[]
  suggested_duration_seconds: number
  suggested_segments: Record<string, number>
}

export type AttemptStatus =
  | 'draft'
  | 'uploaded'
  | 'transcribing'
  | 'evaluating'
  | 'done'
  | 'failed'

export interface ClassroomEvent {
  at: number
  student_id: number
  action: string
  tts_text: string
}

export interface SegmentMark {
  name: string
  at: number
}

export interface Attempt {
  id: number
  lesson_id: number
  mode: string
  status: AttemptStatus
  media_path: string | null
  duration_seconds: number | null
  segment_marks: SegmentMark[]
  classroom_script: ClassroomEvent[]
  error_message: string | null
}

export interface Suggestion {
  at: number
  issue: string
  advice: string
}

export interface EvaluationDimensions {
  content: Record<string, number>
  expression: { 得分: number; [key: string]: unknown }
  time: { 得分: number; [key: string]: unknown }
}

export interface Evaluation {
  attempt_id: number
  dimensions: EvaluationDimensions
  total_score: number
  suggestions: Suggestion[]
  summary: string
}
```

- [ ] **Step 7: 建 `frontend/src/api/client.ts`**

```ts
import axios from 'axios'

const TOKEN_KEY = 'shijiangtai_token'

export const http = axios.create({ baseURL: '/api/v1' })

http.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem(TOKEN_KEY)
      if (window.location.pathname !== '/login') {
        window.location.href = '/login'
      }
    }
    return Promise.reject(error)
  },
)

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}
```

- [ ] **Step 8: 建 `frontend/src/api/auth.ts`**

```ts
import { http } from './client'
import type { TokenResponse, UserOut } from '@/types'

export interface RegisterPayload {
  username: string
  password: string
  display_name: string
  major?: string
  grade?: string
}

export async function register(payload: RegisterPayload): Promise<TokenResponse> {
  return (await http.post<TokenResponse>('/auth/register', payload)).data
}

export async function login(username: string, password: string): Promise<TokenResponse> {
  return (await http.post<TokenResponse>('/auth/login', { username, password })).data
}

export async function fetchMe(): Promise<UserOut> {
  return (await http.get<UserOut>('/auth/me')).data
}
```

- [ ] **Step 9: 建 `frontend/src/api/lessons.ts`**

```ts
import { http } from './client'
import type { Lesson } from '@/types'

export async function fetchLessons(params?: {
  subject?: string
  stage?: string
}): Promise<Lesson[]> {
  return (await http.get<Lesson[]>('/lessons', { params })).data
}

export async function fetchLesson(id: number): Promise<Lesson> {
  return (await http.get<Lesson>(`/lessons/${id}`)).data
}
```

- [ ] **Step 10: 建 `frontend/src/api/attempts.ts`**

```ts
import { http } from './client'
import type { Attempt, Evaluation, SegmentMark } from '@/types'

export async function createAttempt(payload: {
  lesson_id: number
  planned_seconds: number
}): Promise<Attempt> {
  return (await http.post<Attempt>('/attempts', payload)).data
}

export async function uploadMedia(attemptId: number, blob: Blob): Promise<Attempt> {
  const form = new FormData()
  form.append('file', blob, 'recording.webm')
  return (
    await http.post<Attempt>(`/attempts/${attemptId}/media`, form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
  ).data
}

export async function finishAttempt(
  attemptId: number,
  durationSeconds: number,
  segmentMarks: SegmentMark[],
): Promise<Attempt> {
  return (
    await http.post<Attempt>(`/attempts/${attemptId}/finish`, {
      duration_seconds: durationSeconds,
      segment_marks: segmentMarks,
    })
  ).data
}

export async function fetchAttempt(attemptId: number): Promise<Attempt> {
  return (await http.get<Attempt>(`/attempts/${attemptId}`)).data
}

export async function fetchAttempts(): Promise<Attempt[]> {
  return (await http.get<Attempt[]>('/attempts')).data
}

export async function fetchEvaluation(attemptId: number): Promise<Evaluation> {
  return (await http.get<Evaluation>(`/attempts/${attemptId}/evaluation`)).data
}
```

- [ ] **Step 11: 建 `frontend/src/stores/user.ts`**

```ts
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import * as authApi from '@/api/auth'
import { clearToken, getToken, setToken } from '@/api/client'
import type { UserOut } from '@/types'

export const useUserStore = defineStore('user', () => {
  const user = ref<UserOut | null>(null)
  const isLoggedIn = computed(() => Boolean(user.value && getToken()))

  async function login(username: string, password: string): Promise<void> {
    const data = await authApi.login(username, password)
    setToken(data.access_token)
    user.value = data.user
  }

  async function register(payload: authApi.RegisterPayload): Promise<void> {
    const data = await authApi.register(payload)
    setToken(data.access_token)
    user.value = data.user
  }

  async function restore(): Promise<void> {
    if (!getToken()) return
    try {
      user.value = await authApi.fetchMe()
    } catch {
      logout()
    }
  }

  function logout(): void {
    clearToken()
    user.value = null
  }

  return { user, isLoggedIn, login, register, restore, logout }
})
```

- [ ] **Step 12: 建 `frontend/src/router/index.ts`**

```ts
import { createRouter, createWebHistory } from 'vue-router'

import { getToken } from '@/api/client'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue') },
    { path: '/', redirect: '/lessons' },
    {
      path: '/lessons',
      name: 'lessons',
      component: () => import('@/views/LessonListView.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/classroom/:id',
      name: 'classroom',
      component: () => import('@/views/ClassroomView.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/report/:id',
      name: 'report',
      component: () => import('@/views/ReportView.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/history',
      name: 'history',
      component: () => import('@/views/HistoryView.vue'),
      meta: { requiresAuth: true },
    },
  ],
})

router.beforeEach((to) => {
  if (to.meta.requiresAuth && !getToken()) {
    return { name: 'login' }
  }
  return true
})

export default router
```

- [ ] **Step 13: 改 `frontend/src/main.ts`**

```ts
import 'element-plus/dist/index.css'
import { createPinia } from 'pinia'
import { createApp } from 'vue'

import App from './App.vue'
import router from './router'
import './style.css'

// Element Plus 组件由 unplugin-vue-components 自动按需注册(见 vite.config.ts),
// 这里不再 app.use(ElementPlus);全局样式仍整体引入,保证 v-loading 等指令的样式可用。
createApp(App).use(createPinia()).use(router).mount('#app')
```

- [ ] **Step 14: 改 `frontend/src/App.vue`**

```vue
<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'

import { useUserStore } from '@/stores/user'

const userStore = useUserStore()
const router = useRouter()

onMounted(() => {
  void userStore.restore()
})

function handleLogout(): void {
  userStore.logout()
  void router.push({ name: 'login' })
}
</script>

<template>
  <el-container class="app-shell">
    <el-header v-if="userStore.isLoggedIn" class="app-header">
      <span class="brand">师范 AI 试讲台</span>
      <nav class="nav">
        <router-link to="/lessons">试讲题目</router-link>
        <router-link to="/history">我的试讲</router-link>
      </nav>
      <div class="user">
        <span>{{ userStore.user?.display_name }}</span>
        <el-button link type="primary" @click="handleLogout">退出</el-button>
      </div>
    </el-header>
    <el-main>
      <router-view />
    </el-main>
  </el-container>
</template>

<style scoped>
.app-shell {
  min-height: 100vh;
}
.app-header {
  display: flex;
  align-items: center;
  gap: 24px;
  border-bottom: 1px solid var(--el-border-color-light);
}
.brand {
  font-weight: 600;
}
.nav {
  display: flex;
  gap: 16px;
}
.user {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 12px;
}
</style>
```

- [ ] **Step 15: 改 `frontend/src/style.css`**

```css
:root {
  --app-bg: #f5f7fa;
}

* {
  box-sizing: border-box;
}

body {
  margin: 0;
  background: var(--app-bg);
  font-family: system-ui, -apple-system, 'Segoe UI', 'Microsoft YaHei', sans-serif;
}
```

删除模板自带的 `HelloWorld.vue` 与 `App.vue` 原有内容,并删除 `src/components/HelloWorld.vue`、`src/assets/hero.png`。

- [ ] **Step 16: 验证构建**

```bash
cd frontend && npm run build
```

预期:构建成功,无类型错误。

- [ ] **Step 17: 提交**

```bash
git add frontend/package.json frontend/package-lock.json frontend/vite.config.ts \
  frontend/tsconfig.app.json frontend/vitest.config.ts frontend/src
git commit -m "feat(frontend): 前端基座(路由/Pinia/请求层/Element Plus)"
```

---

### Task 2: 纯逻辑工具与单测(radar / classroom)

**Files:**
- Create: `frontend/src/utils/radar.ts`, `frontend/src/utils/classroom.ts`
- Test: `frontend/src/utils/radar.spec.ts`, `frontend/src/utils/classroom.spec.ts`

- [ ] **Step 1: 写失败测试 `frontend/src/utils/radar.spec.ts`**

```ts
import { describe, expect, it } from 'vitest'

import type { EvaluationDimensions } from '@/types'
import { buildRadarData } from './radar'

const dimensions: EvaluationDimensions = {
  content: { 知识点准确性: 80, 逻辑清晰度: 70 },
  expression: { 得分: 90 },
  time: { 得分: 60 },
}

describe('buildRadarData', () => {
  it('把内容维度与规则维度合并成雷达数据', () => {
    const data = buildRadarData(dimensions)
    expect(data).toHaveLength(4)
    expect(data.map((d) => d.name)).toEqual([
      '知识点准确性',
      '逻辑清晰度',
      '教学表达',
      '时间管理',
    ])
    expect(data[2].score).toBe(90)
    expect(data[3].score).toBe(60)
  })

  it('缺失维度时补 0 而不是崩溃', () => {
    const data = buildRadarData({ content: {}, expression: {}, time: {} } as EvaluationDimensions)
    expect(data.map((d) => d.score)).toEqual([0, 0])
  })
})
```

- [ ] **Step 2: 运行,确认失败**

```bash
cd frontend && npx vitest run src/utils/radar.spec.ts
```

预期:FAIL,`Failed to resolve import "./radar"`。

- [ ] **Step 3: 写 `frontend/src/utils/radar.ts`**

```ts
import type { EvaluationDimensions } from '@/types'

export interface RadarDatum {
  name: string
  score: number
}

/**
 * 把评测维度转成 ECharts 雷达图数据。
 * 内容维度逐项展开,教学表达 / 时间管理取规则分。
 */
export function buildRadarData(dimensions: EvaluationDimensions): RadarDatum[] {
  const content = Object.entries(dimensions.content ?? {}).map(([name, score]) => ({
    name,
    score: Number(score) || 0,
  }))
  const expression = Number(dimensions.expression?.得分) || 0
  const time = Number(dimensions.time?.得分) || 0
  return [
    ...content,
    { name: '教学表达', score: expression },
    { name: '时间管理', score: time },
  ]
}
```

- [ ] **Step 4: 运行,确认通过**

```bash
cd frontend && npx vitest run src/utils/radar.spec.ts
```

预期:2 passed。

- [ ] **Step 5: 写失败测试 `frontend/src/utils/classroom.spec.ts`**

```ts
import { describe, expect, it } from 'vitest'

import type { ClassroomEvent } from '@/types'
import { takeDueEvents } from './classroom'

const script: ClassroomEvent[] = [
  { at: 10, student_id: 1, action: 'raise_hand', tts_text: '' },
  { at: 20, student_id: 2, action: 'ask_question', tts_text: '老师,为什么?' },
  { at: 60, student_id: 3, action: 'distracted', tts_text: '' },
]

describe('takeDueEvents', () => {
  it('取出到点的事件并前移游标', () => {
    const result = takeDueEvents(script, 0, 25)
    expect(result.events.map((e) => e.at)).toEqual([10, 20])
    expect(result.cursor).toBe(2)
  })

  it('未到时间时不取事件', () => {
    const result = takeDueEvents(script, 0, 5)
    expect(result.events).toEqual([])
    expect(result.cursor).toBe(0)
  })

  it('重复调用不重放已触发事件', () => {
    const first = takeDueEvents(script, 0, 70)
    const second = takeDueEvents(script, first.cursor, 80)
    expect(first.events).toHaveLength(3)
    expect(second.events).toEqual([])
  })
})
```

- [ ] **Step 6: 运行,确认失败**

```bash
cd frontend && npx vitest run src/utils/classroom.spec.ts
```

预期:FAIL,`Failed to resolve import "./classroom"`。

- [ ] **Step 7: 写 `frontend/src/utils/classroom.ts`**

```ts
import type { ClassroomEvent } from '@/types'

export interface DueEventsResult {
  events: ClassroomEvent[]
  cursor: number
}

/**
 * 取出脚本中 at <= elapsed 且尚未触发的事件,返回新游标。
 * 纯函数:输入相同则输出相同,便于单测。
 */
export function takeDueEvents(
  script: ClassroomEvent[],
  cursor: number,
  elapsed: number,
): DueEventsResult {
  const events: ClassroomEvent[] = []
  let next = cursor
  while (next < script.length && script[next].at <= elapsed) {
    events.push(script[next])
    next += 1
  }
  return { events, cursor: next }
}
```

- [ ] **Step 8: 运行,确认通过**

```bash
cd frontend && npx vitest run
```

预期:5 passed。

- [ ] **Step 9: 提交**

```bash
git add frontend/src/utils
git commit -m "feat(frontend): 雷达图数据与课堂事件调度纯函数(含单测)"
```

---

### Task 3: 登录页

**Files:**
- Create: `frontend/src/views/LoginView.vue`

- [ ] **Step 1: 写 `frontend/src/views/LoginView.vue`**

```vue
<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import { useUserStore } from '@/stores/user'

const MAJORS = ['语文', '数学', '英语', '思政', '幼教']

const router = useRouter()
const userStore = useUserStore()

const mode = ref<'login' | 'register'>('login')
const loading = ref(false)
const error = ref('')

const form = reactive({
  username: '',
  password: '',
  display_name: '',
  major: '语文',
  grade: '2023',
})

async function submit(): Promise<void> {
  error.value = ''
  loading.value = true
  try {
    if (mode.value === 'login') {
      await userStore.login(form.username, form.password)
    } else {
      await userStore.register({
        username: form.username,
        password: form.password,
        display_name: form.display_name,
        major: form.major,
        grade: form.grade,
      })
    }
    await router.push({ name: 'lessons' })
  } catch {
    error.value = mode.value === 'login' ? '用户名或密码错误' : '注册失败,用户名可能已存在'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-wrap">
    <el-card class="login-card">
      <h2>{{ mode === 'login' ? '登录' : '注册师范生账号' }}</h2>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="学号或用户名" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password />
        </el-form-item>
        <template v-if="mode === 'register'">
          <el-form-item label="姓名">
            <el-input v-model="form.display_name" />
          </el-form-item>
          <el-form-item label="专业">
            <el-select v-model="form.major">
              <el-option v-for="m in MAJORS" :key="m" :label="m" :value="m" />
            </el-select>
          </el-form-item>
          <el-form-item label="年级">
            <el-input v-model="form.grade" />
          </el-form-item>
        </template>
        <el-alert v-if="error" :title="error" type="error" :closable="false" />
        <el-button
          class="submit"
          type="primary"
          :loading="loading"
          native-type="submit"
        >
          {{ mode === 'login' ? '登录' : '注册并登录' }}
        </el-button>
        <el-button link type="primary" @click="mode = mode === 'login' ? 'register' : 'login'">
          {{ mode === 'login' ? '没有账号?去注册' : '已有账号?去登录' }}
        </el-button>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.login-wrap {
  display: flex;
  justify-content: center;
  padding-top: 8vh;
}
.login-card {
  width: 360px;
}
.submit {
  width: 100%;
  margin: 12px 0;
}
</style>
```

- [ ] **Step 2: 验证构建**

```bash
cd frontend && npm run build
```

预期:成功。

- [ ] **Step 3: 手工验证**

起后端 + 前端,访问 <http://localhost:5173/login>,注册一个账号,确认跳转到 `/lessons`。

- [ ] **Step 4: 提交**

```bash
git add frontend/src/views/LoginView.vue
git commit -m "feat(frontend): 登录/注册页"
```

---

### Task 4: 题目列表页与历史页

**Files:**
- Create: `frontend/src/views/LessonListView.vue`, `frontend/src/views/HistoryView.vue`

- [ ] **Step 1: 写 `frontend/src/views/LessonListView.vue`**

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { createAttempt } from '@/api/attempts'
import { fetchLessons } from '@/api/lessons'
import type { Lesson } from '@/types'

const SUBJECTS = ['语文', '数学', '英语', '思政', '幼教']
const STAGES = ['小学', '初中', '高中', '学前']

const router = useRouter()
const lessons = ref<Lesson[]>([])
const loading = ref(false)
const subject = ref('')
const stage = ref('')
const error = ref('')

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    lessons.value = await fetchLessons({
      subject: subject.value || undefined,
      stage: stage.value || undefined,
    })
  } catch {
    error.value = '加载题目失败,请确认后端已启动'
  } finally {
    loading.value = false
  }
}

async function start(lesson: Lesson): Promise<void> {
  const attempt = await createAttempt({
    lesson_id: lesson.id,
    planned_seconds: lesson.suggested_duration_seconds,
  })
  await router.push({ name: 'classroom', params: { id: attempt.id } })
}

onMounted(load)
</script>

<template>
  <div>
    <h2>试讲题目</h2>
    <div class="filters">
      <el-select v-model="subject" clearable placeholder="学科" @change="load">
        <el-option v-for="s in SUBJECTS" :key="s" :label="s" :value="s" />
      </el-select>
      <el-select v-model="stage" clearable placeholder="学段" @change="load">
        <el-option v-for="s in STAGES" :key="s" :label="s" :value="s" />
      </el-select>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div v-loading="loading" class="grid">
      <el-card v-for="lesson in lessons" :key="lesson.id" class="lesson-card">
        <h3>{{ lesson.title }}</h3>
        <div class="tags">
          <el-tag size="small">{{ lesson.subject }}</el-tag>
          <el-tag size="small" type="info">{{ lesson.stage }}</el-tag>
          <el-tag size="small" type="info">{{ lesson.textbook }}</el-tag>
        </div>
        <p class="content">{{ lesson.content }}</p>
        <div class="points">
          <span v-for="p in lesson.key_points" :key="p" class="point">· {{ p }}</span>
        </div>
        <el-button type="primary" @click="start(lesson)">开始试讲</el-button>
      </el-card>
    </div>
    <el-empty v-if="!loading && lessons.length === 0" description="暂无题目" />
  </div>
</template>

<style scoped>
.filters {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}
.tags {
  display: flex;
  gap: 8px;
  margin: 8px 0;
}
.content {
  color: var(--el-text-color-regular);
  font-size: 13px;
}
.points {
  display: flex;
  flex-direction: column;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 12px;
}
</style>
```

- [ ] **Step 2: 写 `frontend/src/views/HistoryView.vue`**

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'

import { fetchAttempts } from '@/api/attempts'
import type { Attempt, AttemptStatus } from '@/types'

const STATUS_TEXT: Record<AttemptStatus, string> = {
  draft: '未录制',
  uploaded: '待评测',
  transcribing: '转写中',
  evaluating: '评测中',
  done: '已完成',
  failed: '失败',
}

const attempts = ref<Attempt[]>([])
const loading = ref(false)

onMounted(async () => {
  loading.value = true
  try {
    attempts.value = await fetchAttempts()
  } finally {
    loading.value = false
  }
})

function tagType(status: AttemptStatus): 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'done') return 'success'
  if (status === 'failed') return 'danger'
  if (status === 'draft' || status === 'uploaded') return 'info'
  return 'warning'
}
</script>

<template>
  <div>
    <h2>我的试讲</h2>
    <el-table v-loading="loading" :data="attempts">
      <el-table-column prop="id" label="编号" width="80" />
      <el-table-column prop="lesson_id" label="题目 ID" width="100" />
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <el-tag :type="tagType(row.status)">{{ STATUS_TEXT[row.status as AttemptStatus] }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="时长(秒)" width="120">
        <template #default="{ row }">
          {{ row.duration_seconds ? Math.round(row.duration_seconds) : '-' }}
        </template>
      </el-table-column>
      <el-table-column label="操作">
        <template #default="{ row }">
          <router-link :to="{ name: 'classroom', params: { id: row.id } }">
            <el-button link type="primary">查看课堂</el-button>
          </router-link>
          <router-link
            v-if="row.status === 'done'"
            :to="{ name: 'report', params: { id: row.id } }"
          >
            <el-button link type="primary">评测报告</el-button>
          </router-link>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && attempts.length === 0" description="还没有试讲记录" />
  </div>
</template>
```

- [ ] **Step 3: 验证构建并手工验证**

```bash
cd frontend && npm run build
```

预期:成功。浏览器确认 `/lessons` 显示 5 条题目,`/history` 显示记录表。

- [ ] **Step 4: 提交**

```bash
git add frontend/src/views
git commit -m "feat(frontend): 题目列表页与试讲历史页"
```

---

### Task 5: 录制与课堂脚本组合式函数

**Files:**
- Create: `frontend/src/composables/useRecorder.ts`, `frontend/src/composables/useClassroomScript.ts`, `frontend/src/composables/useAttemptPolling.ts`

- [ ] **Step 1: 写 `frontend/src/composables/useRecorder.ts`**

```ts
import { ref } from 'vue'

export interface RecordingResult {
  blob: Blob
  durationSeconds: number
}

/**
 * 封装 MediaRecorder:同时录麦克风与摄像头。
 * 组件卸载时必须调用 dispose(),否则摄像头指示灯不灭。
 */
export function useRecorder() {
  const isRecording = ref(false)
  const error = ref('')

  let recorder: MediaRecorder | null = null
  let stream: MediaStream | null = null
  let chunks: Blob[] = []
  let startedAt = 0

  function releaseStream(): void {
    stream?.getTracks().forEach((track) => track.stop())
    stream = null
  }

  async function start(): Promise<boolean> {
    error.value = ''
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true, video: true })
    } catch {
      error.value = '无法访问摄像头/麦克风,请检查浏览器权限'
      return false
    }
    chunks = []
    const preferWebm = 'video/webm;codecs=vp8,opus'
    const options: MediaRecorderOptions = MediaRecorder.isTypeSupported(preferWebm)
      ? { mimeType: preferWebm }
      : {}
    recorder = new MediaRecorder(stream, options)
    recorder.ondataavailable = (event) => {
      if (event.data.size > 0) chunks.push(event.data)
    }
    recorder.start(1000)
    startedAt = performance.now()
    isRecording.value = true
    return true
  }

  function stop(): Promise<RecordingResult> {
    return new Promise((resolve) => {
      if (!recorder) {
        resolve({ blob: new Blob(), durationSeconds: 0 })
        return
      }
      const active = recorder
      active.onstop = () => {
        const blob = new Blob(chunks, { type: active.mimeType || 'video/webm' })
        const durationSeconds = (performance.now() - startedAt) / 1000
        releaseStream()
        isRecording.value = false
        recorder = null
        resolve({ blob, durationSeconds })
      }
      active.stop()
    })
  }

  function dispose(): void {
    if (recorder && recorder.state !== 'inactive') recorder.stop()
    releaseStream()
    isRecording.value = false
  }

  return { isRecording, error, start, stop, dispose }
}
```

- [ ] **Step 2: 写 `frontend/src/composables/useClassroomScript.ts`**

```ts
import { computed, ref, type Ref } from 'vue'

import type { ClassroomEvent, SegmentMark } from '@/types'
import { takeDueEvents } from '@/utils/classroom'

export type StudentStatus = '专注' | '举手' | '走神' | '回答' | '说话'

export interface StudentState {
  id: number
  name: string
  status: StudentStatus
}

const ACTION_TO_STATUS: Record<string, StudentStatus> = {
  raise_hand: '举手',
  ask_question: '举手',
  answer_correct: '回答',
  answer_wrong: '回答',
  distracted: '走神',
  chatter: '说话',
}

const TICK_MS = 100

/**
 * 按脚本时间表驱动虚拟学生行为,并用浏览器 TTS 播放提问。
 * 提供 elapsed(秒)、marks(环节打点)与 start/stop/addMark。
 */
export function useClassroomScript(
  script: Ref<ClassroomEvent[]>,
  studentCount: number,
) {
  const elapsed = ref(0)
  const activeEvent = ref<ClassroomEvent | null>(null)
  const marks = ref<SegmentMark[]>([])
  const students = ref<StudentState[]>(
    Array.from({ length: studentCount }, (_, i) => ({
      id: i + 1,
      name: `学生${i + 1}`,
      status: '专注' as StudentStatus,
    })),
  )

  let timer: number | null = null
  let cursor = 0

  function speak(text: string): void {
    if (!text || typeof window === 'undefined' || !window.speechSynthesis) return
    const utterance = new SpeechSynthesisUtterance(text)
    utterance.lang = 'zh-CN'
    window.speechSynthesis.cancel()
    window.speechSynthesis.speak(utterance)
  }

  function fire(event: ClassroomEvent): void {
    activeEvent.value = event
    const student = students.value.find((s) => s.id === event.student_id)
    if (student) {
      student.status = ACTION_TO_STATUS[event.action] ?? '专注'
    }
    if (event.tts_text) speak(event.tts_text)
  }

  function tick(): void {
    elapsed.value = Number((elapsed.value + TICK_MS / 1000).toFixed(1))
    const due = takeDueEvents(script.value, cursor, elapsed.value)
    cursor = due.cursor
    due.events.forEach(fire)
  }

  function start(): void {
    if (timer !== null) return
    timer = window.setInterval(tick, TICK_MS)
  }

  function stop(): void {
    if (timer !== null) window.clearInterval(timer)
    timer = null
    window.speechSynthesis?.cancel()
  }

  function addMark(name: string): void {
    marks.value.push({ name, at: elapsed.value })
  }

  function reset(): void {
    stop()
    elapsed.value = 0
    cursor = 0
    marks.value = []
    activeEvent.value = null
    students.value.forEach((s) => (s.status = '专注'))
  }

  const elapsedText = computed(() => {
    const total = Math.floor(elapsed.value)
    const mm = String(Math.floor(total / 60)).padStart(2, '0')
    const ss = String(total % 60).padStart(2, '0')
    return `${mm}:${ss}`
  })

  return {
    elapsed,
    elapsedText,
    activeEvent,
    marks,
    students,
    start,
    stop,
    addMark,
    reset,
  }
}
```

- [ ] **Step 3: 写 `frontend/src/composables/useAttemptPolling.ts`**

```ts
import { onUnmounted, ref } from 'vue'

import { fetchAttempt } from '@/api/attempts'
import type { Attempt, AttemptStatus } from '@/types'

const FINAL_STATUSES: AttemptStatus[] = ['done', 'failed']

/**
 * 轮询试讲状态,直到 done/failed 或超时。组件卸载时自动停止。
 */
export function useAttemptPolling(attemptId: number, intervalMs = 2000, timeoutMs = 180000) {
  const attempt = ref<Attempt | null>(null)
  const isPolling = ref(false)
  const timedOut = ref(false)

  let timer: number | null = null
  let deadline = 0

  async function poll(): Promise<void> {
    try {
      const latest = await fetchAttempt(attemptId)
      attempt.value = latest
      if (FINAL_STATUSES.includes(latest.status)) {
        stop()
        return
      }
    } catch {
      // 单次失败不终止轮询
    }
    if (Date.now() > deadline) {
      timedOut.value = true
      stop()
    }
  }

  function start(): void {
    if (isPolling.value) return
    isPolling.value = true
    timedOut.value = false
    deadline = Date.now() + timeoutMs
    timer = window.setInterval(() => void poll(), intervalMs)
    void poll()
  }

  function stop(): void {
    if (timer !== null) window.clearInterval(timer)
    timer = null
    isPolling.value = false
  }

  onUnmounted(stop)

  return { attempt, isPolling, timedOut, start, stop }
}
```

- [ ] **Step 4: 验证构建**

```bash
cd frontend && npm run build
```

预期:成功。

- [ ] **Step 5: 提交**

```bash
git add frontend/src/composables
git commit -m "feat(frontend): 录制/课堂脚本调度/评测轮询组合式函数"
```

---

### Task 6: 课堂组件(三栏布局)

**Files:**
- Create: `frontend/src/components/ClassroomBoard.vue`, `StudentPanel.vue`, `RecorderControls.vue`, `SegmentTimeline.vue`

- [ ] **Step 1: 写 `frontend/src/components/ClassroomBoard.vue`**

```vue
<script setup lang="ts">
import type { ClassroomEvent } from '@/types'

defineProps<{
  lessonTitle: string
  lessonContent: string
  keyPoints: string[]
  activeEvent: ClassroomEvent | null
}>()
</script>

<template>
  <section class="board">
    <header class="board-head">
      <h3>{{ lessonTitle }}</h3>
      <span class="hint">黑板 / PPT 展示区</span>
    </header>
    <div class="board-body">
      <p class="content">{{ lessonContent }}</p>
      <ul class="points">
        <li v-for="point in keyPoints" :key="point">{{ point }}</li>
      </ul>
    </div>
    <footer v-if="activeEvent" class="bubble">
      <strong>学生{{ activeEvent.student_id }}:</strong>
      <span>{{ activeEvent.tts_text || '（举手）' }}</span>
    </footer>
  </section>
</template>

<style scoped>
.board {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #1f2733;
  color: #e6edf6;
  border-radius: 8px;
  padding: 16px;
}
.board-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  border-bottom: 1px solid #33415a;
  padding-bottom: 8px;
}
.hint {
  font-size: 12px;
  color: #8fa0bd;
}
.board-body {
  flex: 1;
  padding: 16px 0;
}
.content {
  line-height: 1.8;
}
.points li {
  color: #a9bcd6;
  font-size: 14px;
}
.bubble {
  background: #2b3b52;
  border-radius: 8px;
  padding: 10px 12px;
  font-size: 14px;
}
</style>
```

- [ ] **Step 2: 写 `frontend/src/components/StudentPanel.vue`**

```vue
<script setup lang="ts">
import type { StudentState, StudentStatus } from '@/composables/useClassroomScript'

defineProps<{ students: StudentState[]; activeEventId: number | null }>()

const STATUS_CLASS: Record<StudentStatus, string> = {
  专注: 'ok',
  举手: 'hand',
  走神: 'idle',
  回答: 'answer',
  说话: 'talk',
}
</script>

<template>
  <section class="panel">
    <h3>虚拟学生</h3>
    <div class="list">
      <div
        v-for="student in students"
        :key="student.id"
        class="student"
        :class="[STATUS_CLASS[student.status], { active: student.id === activeEventId }]"
      >
        <div class="avatar">{{ student.name.slice(-1) }}</div>
        <div class="meta">
          <span class="name">{{ student.name }}</span>
          <span class="status">{{ student.status }}</span>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.panel {
  height: 100%;
  background: #fff;
  border-radius: 8px;
  padding: 12px;
  overflow-y: auto;
}
.list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.student {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px;
  border-radius: 6px;
  border-left: 4px solid #cbd5e1;
  background: #f8fafc;
}
.student.ok {
  border-left-color: #22c55e;
}
.student.hand {
  border-left-color: #eab308;
}
.student.idle {
  border-left-color: #94a3b8;
  opacity: 0.75;
}
.student.answer {
  border-left-color: #3b82f6;
}
.student.talk {
  border-left-color: #f97316;
}
.student.active {
  box-shadow: 0 0 0 2px #eab308;
}
.avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: #dbeafe;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
}
.meta {
  display: flex;
  flex-direction: column;
}
.name {
  font-size: 13px;
}
.status {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
```

- [ ] **Step 3: 写 `frontend/src/components/SegmentTimeline.vue`**

```vue
<script setup lang="ts">
import type { SegmentMark } from '@/types'

defineProps<{
  segments: string[]
  marks: SegmentMark[]
  elapsedText: string
  disabled: boolean
}>()

const emit = defineEmits<{ (event: 'mark', name: string): void }>()
</script>

<template>
  <section class="timeline">
    <h3>教学环节</h3>
    <div class="clock">{{ elapsedText }}</div>
    <el-button
      v-for="segment in segments"
      :key="segment"
      class="mark-btn"
      :disabled="disabled"
      @click="emit('mark', segment)"
    >
      进入「{{ segment }}」
    </el-button>
    <div class="marks">
      <div v-for="(mark, index) in marks" :key="index" class="mark-row">
        <span>{{ mark.name }}</span>
        <span class="at">{{ mark.at.toFixed(1) }}s</span>
      </div>
    </div>
  </section>
</template>

<style scoped>
.timeline {
  height: 100%;
  background: #fff;
  border-radius: 8px;
  padding: 12px;
  overflow-y: auto;
}
.clock {
  font-size: 28px;
  font-variant-numeric: tabular-nums;
  margin-bottom: 12px;
}
.mark-btn {
  width: 100%;
  margin-bottom: 8px;
}
.marks {
  margin-top: 12px;
  border-top: 1px solid var(--el-border-color-lighter);
  padding-top: 8px;
}
.mark-row {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.at {
  color: var(--el-text-color-secondary);
}
</style>
```

- [ ] **Step 4: 写 `frontend/src/components/RecorderControls.vue`**

```vue
<script setup lang="ts">
defineProps<{
  isRecording: boolean
  busy: boolean
  error: string
}>()

const emit = defineEmits<{
  (event: 'start'): void
  (event: 'finish'): void
}>()
</script>

<template>
  <div class="controls">
    <el-button
      v-if="!isRecording"
      type="primary"
      size="large"
      :loading="busy"
      @click="emit('start')"
    >
      开始试讲
    </el-button>
    <el-button
      v-else
      type="danger"
      size="large"
      :loading="busy"
      @click="emit('finish')"
    >
      结束试讲并提交评测
    </el-button>
    <span v-if="isRecording" class="rec">● 录制中</span>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
  </div>
</template>

<style scoped>
.controls {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 12px;
  background: #fff;
  border-radius: 8px;
}
.rec {
  color: #ef4444;
  font-size: 14px;
}
</style>
```

- [ ] **Step 5: 验证构建**

```bash
cd frontend && npm run build
```

预期:成功。

- [ ] **Step 6: 提交**

```bash
git add frontend/src/components
git commit -m "feat(frontend): 课堂三栏组件(黑板/学生面板/环节时间轴/录制控件)"
```

---

### Task 7: 课堂页(串联录制、脚本、提交)

**Files:**
- Create: `frontend/src/views/ClassroomView.vue`

- [ ] **Step 1: 写 `frontend/src/views/ClassroomView.vue`**

```vue
<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { fetchAttempt, finishAttempt, uploadMedia } from '@/api/attempts'
import { fetchLesson } from '@/api/lessons'
import ClassroomBoard from '@/components/ClassroomBoard.vue'
import RecorderControls from '@/components/RecorderControls.vue'
import SegmentTimeline from '@/components/SegmentTimeline.vue'
import StudentPanel from '@/components/StudentPanel.vue'
import { useAttemptPolling } from '@/composables/useAttemptPolling'
import { useClassroomScript } from '@/composables/useClassroomScript'
import { useRecorder } from '@/composables/useRecorder'
import type { ClassroomEvent, Lesson } from '@/types'

const route = useRoute()
const router = useRouter()
const attemptId = Number(route.params.id)

const lesson = ref<Lesson | null>(null)
const script = ref<ClassroomEvent[]>([])
const loadError = ref('')
const submitError = ref('')
const busy = ref(false)

const recorder = useRecorder()
const classroom = useClassroomScript(script, 12)
const polling = useAttemptPolling(attemptId)

const segments = computed(() => (lesson.value ? Object.keys(lesson.value.suggested_segments) : []))
const activeEventId = computed(() => classroom.activeEvent.value?.student_id ?? null)

onMounted(async () => {
  try {
    const attempt = await fetchAttempt(attemptId)
    script.value = attempt.classroom_script
    lesson.value = await fetchLesson(attempt.lesson_id)
  } catch {
    loadError.value = '加载试讲信息失败'
  }
})

onUnmounted(() => {
  recorder.dispose()
  classroom.stop()
})

async function handleStart(): Promise<void> {
  submitError.value = ''
  const ok = await recorder.start()
  if (ok) classroom.start()
}

async function handleFinish(): Promise<void> {
  busy.value = true
  submitError.value = ''
  try {
    classroom.stop()
    const { blob, durationSeconds } = await recorder.stop()
    await uploadMedia(attemptId, blob)
    await finishAttempt(attemptId, durationSeconds, classroom.marks.value)
    await router.push({ name: 'report', params: { id: attemptId } })
  } catch {
    submitError.value = '提交失败,请重试'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div>
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" />
    <div class="layout">
      <SegmentTimeline
        class="col-left"
        :segments="segments"
        :marks="classroom.marks.value"
        :elapsed-text="classroom.elapsedText.value"
        :disabled="!recorder.isRecording.value"
        @mark="classroom.addMark"
      />
      <ClassroomBoard
        class="col-center"
        :lesson-title="lesson?.title ?? ''"
        :lesson-content="lesson?.content ?? ''"
        :key-points="lesson?.key_points ?? []"
        :active-event="classroom.activeEvent.value"
      />
      <StudentPanel
        class="col-right"
        :students="classroom.students.value"
        :active-event-id="activeEventId"
      />
    </div>
    <RecorderControls
      class="controls"
      :is-recording="recorder.isRecording.value"
      :busy="busy"
      :error="recorder.error.value || submitError"
      @start="handleStart"
      @finish="handleFinish"
    />
  </div>
</template>

<style scoped>
.layout {
  display: grid;
  grid-template-columns: 220px 1fr 260px;
  gap: 16px;
  height: 60vh;
  margin-bottom: 16px;
}
.col-left,
.col-center,
.col-right {
  min-height: 0;
}
</style>
```

> 说明:`useClassroomScript` 返回的是 `ref`,在模板中经 `.value` 访问;若嫌冗长,可在后续统一改用 `storeToRefs` 风格的解构,但 MVP 保持直白。

- [ ] **Step 2: 验证构建**

```bash
cd frontend && npm run build
```

预期:成功。

- [ ] **Step 3: 手工验证**

起后端 + 前端,登录 → 选题目 → 开始试讲(授权摄像头)。预期:计时器走字,虚拟学生按脚本变状态,举手/提问时听到中文朗读。点环节按钮有打点记录。结束后跳转报告页。

- [ ] **Step 4: 提交**

```bash
git add frontend/src/views/ClassroomView.vue
git commit -m "feat(frontend): 课堂页串联录制、脚本调度与提交"
```

---

### Task 8: 报告页与历史回放

**Files:**
- Create: `frontend/src/components/RadarChart.vue`, `frontend/src/components/ReportCard.vue`, `frontend/src/views/ReportView.vue`

- [ ] **Step 1: 写 `frontend/src/components/RadarChart.vue`**

```vue
<script setup lang="ts">
import { RadarChart as EChartsRadar } from 'echarts/charts'
import { LegendComponent, TooltipComponent } from 'echarts/components'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { computed } from 'vue'
import VChart from 'vue-echarts'

import type { EvaluationDimensions } from '@/types'
import { buildRadarData } from '@/utils/radar'

use([EChartsRadar, TooltipComponent, LegendComponent, CanvasRenderer])

const props = defineProps<{ dimensions: EvaluationDimensions }>()

const option = computed(() => {
  const data = buildRadarData(props.dimensions)
  const max = 100
  return {
    tooltip: {},
    legend: { bottom: 0, data: ['得分'] },
    radar: {
      indicator: data.map((d) => ({ name: d.name, max })),
      radius: '65%',
    },
    series: [
      {
        type: 'radar',
        name: '得分',
        data: [{ value: data.map((d) => d.score), name: '得分' }],
        areaStyle: { opacity: 0.3 },
      },
    ],
  }
})
</script>

<template>
  <VChart class="chart" :option="option" autoresize />
</template>

<style scoped>
.chart {
  height: 360px;
}
</style>
```

- [ ] **Step 2: 写 `frontend/src/components/ReportCard.vue`**

```vue
<script setup lang="ts">
import { computed } from 'vue'

import type { Evaluation, Suggestion } from '@/types'

const props = defineProps<{ evaluation: Evaluation }>()

const emit = defineEmits<{ (event: 'seek', seconds: number): void }>()

const contentEntries = computed(() =>
  Object.entries(props.evaluation.dimensions.content ?? {}).map(([name, score]) => ({
    name,
    score: Number(score),
  })),
)

function scoreType(score: number): 'success' | 'warning' | 'danger' {
  if (score >= 85) return 'success'
  if (score >= 70) return 'warning'
  return 'danger'
}

function handleSuggestion(suggestion: Suggestion): void {
  emit('seek', suggestion.at)
}
</script>

<template>
  <div class="report">
    <div class="total">
      <span class="label">总分</span>
      <span class="value">{{ evaluation.total_score }}</span>
    </div>
    <h3>教学内容</h3>
    <div class="dims">
      <div v-for="item in contentEntries" :key="item.name" class="dim">
        <span>{{ item.name }}</span>
        <el-tag :type="scoreType(item.score)">{{ item.score }}</el-tag>
      </div>
    </div>
    <h3>总评</h3>
    <p class="summary">{{ evaluation.summary }}</p>
    <h3>问题定位与建议</h3>
    <el-empty v-if="evaluation.suggestions.length === 0" description="暂无建议" />
    <ul class="suggestions">
      <li
        v-for="(suggestion, index) in evaluation.suggestions"
        :key="index"
        @click="handleSuggestion(suggestion)"
      >
        <el-tag size="small" type="warning">{{ suggestion.at.toFixed(0) }}s</el-tag>
        <span class="issue">{{ suggestion.issue }}</span>
        <span class="advice">{{ suggestion.advice }}</span>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.total {
  display: flex;
  align-items: baseline;
  gap: 12px;
}
.value {
  font-size: 40px;
  font-weight: 600;
  color: var(--el-color-primary);
}
.dims {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 8px;
}
.dim {
  display: flex;
  justify-content: space-between;
  padding: 8px 12px;
  background: #f8fafc;
  border-radius: 6px;
}
.summary {
  background: #f8fafc;
  padding: 12px;
  border-radius: 6px;
  line-height: 1.7;
}
.suggestions {
  list-style: none;
  padding: 0;
}
.suggestions li {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  cursor: pointer;
}
.suggestions li:hover {
  background: #f8fafc;
}
.issue {
  font-weight: 500;
}
.advice {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
```

- [ ] **Step 3: 写 `frontend/src/views/ReportView.vue`**

```vue
<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import { fetchAttempt, fetchEvaluation } from '@/api/attempts'
import RadarChart from '@/components/RadarChart.vue'
import ReportCard from '@/components/ReportCard.vue'
import { useAttemptPolling } from '@/composables/useAttemptPolling'
import type { Attempt, Evaluation } from '@/types'

const route = useRoute()
const attemptId = Number(route.params.id)

const evaluation = ref<Evaluation | null>(null)
const attempt = ref<Attempt | null>(null)
const loadError = ref('')
const mediaUrl = ref('')
const player = ref<HTMLVideoElement | null>(null)

const polling = useAttemptPolling(attemptId)

async function loadEvaluation(): Promise<void> {
  try {
    evaluation.value = await fetchEvaluation(attemptId)
  } catch {
    loadError.value = '评测报告尚未生成或生成失败'
  }
}

function seek(seconds: number): void {
  if (player.value) {
    player.value.currentTime = seconds
    void player.value.play()
  }
}

onMounted(async () => {
  attempt.value = await fetchAttempt(attemptId)
  if (attempt.value.media_path) {
    mediaUrl.value = `/media/${attempt.value.media_path}`
  }
  if (attempt.value.status === 'done') {
    await loadEvaluation()
  } else {
    polling.start()
  }
})

// 轮询到 done 后自动拉报告
watch(
  () => polling.attempt.value?.status,
  async (status) => {
    if (status === 'done') await loadEvaluation()
  },
)
</script>

<template>
  <div>
    <div v-if="polling.isPolling.value" class="waiting">
      <el-progress :percentage="100" :indeterminate="true" :show-text="false" />
      <p>AI 正在评测中(转写 → 规则计算 → 内容评分),请稍候…</p>
    </div>
    <el-alert
      v-if="polling.timedOut.value"
      title="评测超时,请到「我的试讲」重试"
      type="warning"
      :closable="false"
    />
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" />

    <div class="report-layout">
      <div class="left">
        <video
          v-if="mediaUrl"
          ref="player"
          class="player"
          :src="mediaUrl"
          controls
        />
        <el-empty v-else description="无录制回放" />
        <RadarChart v-if="evaluation" :dimensions="evaluation.dimensions" />
      </div>
      <div class="right">
        <ReportCard v-if="evaluation" :evaluation="evaluation" @seek="seek" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.waiting {
  margin-bottom: 16px;
}
.report-layout {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.player {
  width: 100%;
  border-radius: 8px;
  background: #000;
}
</style>
```

- [ ] **Step 4: 验证构建**

```bash
cd frontend && npm run build
```

预期:成功。

- [ ] **Step 5: 手工验证**

完成一次试讲后跳到报告页。预期:进度条 → 雷达图 + 各维度分 + 总评 + 建议列表;点击建议跳转回放对应时间。

- [ ] **Step 6: 提交**

```bash
git add frontend/src/components frontend/src/views
git commit -m "feat(frontend): 报告页(雷达图/建议/回放)与历史回放"
```

---

## 前端完成标准

```bash
cd frontend && npm run test && npm run build
```

单测全绿、构建通过,且手工走通:注册 → 选题 → 试讲录制 → 提交 → 报告。

## 已知限制

- 虚拟学生语音用浏览器 Web SpeechSynthesis(Chrome/Edge 中文音色可用)。云端 TTS 待厂商确定后替换。
- 报告中的「教学内容」分数基于后端 `FakeAsrClient` 的合成转写,除非后端接入真实 ASR 厂商。
