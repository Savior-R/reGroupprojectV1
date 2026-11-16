import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { getToken } from '../api/auth'
import { decodeJwt, hasRole, homeFor, isAdmin } from '../api/jwt'

/**
 * 路由表（09 §4.1）。
 *
 * M3 页面沿用原有的 dev 身份；M1 的登录 / 注册 / 账号安全 / 管理端页面
 * 走 JWT 鉴权，由下面的守卫控制。全站切换到 JWT 时只需把 M3 页面
 * 一并标记 requiresAuth。
 */

declare module 'vue-router' {
  interface RouteMeta {
    /** 需要有效 JWT；否则跳登录 */
    requiresAuth?: boolean
    /** 仅管理员可访问 */
    adminOnly?: boolean
    /** 仅学生可访问（M3 学生端页面，教师端页面待 M3 提供） */
    studentOnly?: boolean
    /** 管理员已建校才能访问；未建校跳 /admin/setup */
    requiresSchool?: boolean
  }
}

export const routes: RouteRecordRaw[] = [
  {
    path: '/',
    // 按角色落地：管理员 → 管理端，学生 → 任务中心，教师 → 账号安全
    redirect: () => {
      const claims = decodeJwt(getToken())
      return claims ? homeFor(claims) : '/login'
    },
  },
  // 学生任务列表与详情：套应用外壳（顶栏导航 + 退出登录）；
  // 课堂 / 面试 / 复盘是沉浸式全屏页，保持独立布局
  {
    path: '/student/tasks',
    component: () => import('../views/admin/AdminShell.vue'),
    meta: { requiresAuth: true, studentOnly: true },
    children: [
      {
        path: '',
        name: 'student-tasks',
        component: () => import('../views/task/StudentTasksPage.vue'),
      },
      {
        path: ':taskId',
        name: 'student-task-detail',
        component: () => import('../views/task/StudentTaskDetailPage.vue'),
      },
    ],
  },
  {
    path: '/student/sessions/:sessionId/classroom',
    name: 'student-classroom',
    component: () => import('../views/classroom/ClassroomPage.vue'),
    meta: { requiresAuth: true, studentOnly: true },
  },
  {
    path: '/student/sessions/:sessionId/interview',
    name: 'student-interview',
    component: () => import('../views/classroom/InterviewPage.vue'),
    meta: { requiresAuth: true, studentOnly: true },
  },
  {
    path: '/student/sessions/:sessionId/review',
    name: 'student-review',
    component: () => import('../views/replay/ReviewPage.vue'),
    meta: { requiresAuth: true, studentOnly: true },
  },

  // ------------------------------------------------ M1 账号 / 组织 / 认证
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/auth/LoginView.vue'),
  },
  {
    path: '/register',
    name: 'register',
    component: () => import('../views/auth/RegisterView.vue'),
  },
  {
    path: '/register/oauth',
    name: 'register-oauth',
    component: () => import('../views/auth/OAuthRegisterView.vue'),
  },
  {
    path: '/settings/security',
    name: 'settings-security',
    component: () => import('../views/admin/SecurityView.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/admin/setup',
    name: 'admin-setup',
    component: () => import('../views/admin/SetupView.vue'),
    meta: { requiresAuth: true, adminOnly: true },
  },
  {
    path: '/admin/org',
    name: 'admin-org',
    component: () => import('../views/admin/OrgView.vue'),
    meta: { requiresAuth: true, adminOnly: true, requiresSchool: true },
  },
  {
    path: '/admin/teachers',
    name: 'admin-teachers',
    component: () => import('../views/admin/TeachersView.vue'),
    meta: { requiresAuth: true, adminOnly: true, requiresSchool: true },
  },
  {
    path: '/admin',
    redirect: '/admin/org',
  },

  {
    path: '/403',
    name: 'forbidden',
    component: () => import('../views/errors/ForbiddenPage.vue'),
  },
  {
    path: '/404',
    component: () => import('../views/errors/NotFoundPage.vue'),
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('../views/errors/NotFoundPage.vue'),
  },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

/**
 * 守卫（03 §3.9「路由守卫」）：
 * - 无 token → /login
 * - 无 admin 角色访问管理端 → 403（教师被授予 admin 后可进，见 V14）
 * - 非学生访问学生端页面 → 403（教师端页面待 M3 提供）
 * - 管理员角色但未建校 → /admin/setup
 * - 已登录再访问登录 / 注册页 → 回各自首页
 */
router.beforeEach((to) => {
  const claims = decodeJwt(getToken())

  if (to.meta.requiresAuth && !claims) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.meta.adminOnly && !isAdmin(claims)) {
    return { path: '/403' }
  }
  if (to.meta.studentOnly && !hasRole(claims, 'student')) {
    return { path: '/403' }
  }
  if (to.meta.requiresSchool && !claims?.school_id) {
    return { path: '/admin/setup' }
  }
  if (claims && (to.name === 'login' || to.name === 'register')) {
    return homeFor(claims)
  }
  return true
})
