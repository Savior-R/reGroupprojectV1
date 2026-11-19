import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { getToken } from '../api/auth'
import { decodeJwt, hasRole, homeFor, isAdmin } from '../api/jwt'
import type { Role } from '../api/jwt'
import { courseRoutes } from '../views/course/course.routes'
import { questionRoutes } from '../views/question/routes'
import { trainingRoutes } from '../views/training/routes'

/**
 * 路由表（09 §4.1）。
 *
 * 约定：
 * - 登录后的普通页面统一 `meta.layout = 'app'`，由 App.vue 套 AppShell。
 * - 课堂 / 面试 / 回看 / 训练房间使用 `meta.layout = 'immersive'`。
 * - 登录 / 注册使用 `meta.layout = 'auth'`，403 / 404 使用 `blank`。
 * - `meta.roles` 与后端角色对齐；`adminOnly` / `requiresSchool` 保留给管理端。
 * - 文档中的旧路径保留重定向，规范路径以本文件为准。
 */

declare module 'vue-router' {
  interface RouteMeta {
    /** 需要有效 JWT；否则跳登录 */
    requiresAuth?: boolean
    /** 允许访问的角色；为空表示所有登录用户 */
    roles?: Role[]
    /** 页面布局：app / auth / immersive / blank */
    layout?: 'app' | 'auth' | 'immersive' | 'blank'
    /** 浏览器标题 */
    title?: string
    /** 仅管理员可访问 */
    adminOnly?: boolean
    /** 仅学生可访问（保留兼容，优先使用 roles） */
    studentOnly?: boolean
    /** 管理员已建校才能访问；未建校跳 /admin/setup */
    requiresSchool?: boolean
  }
}

export const routes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: () => {
      const claims = decodeJwt(getToken())
      return claims ? homeFor(claims) : '/login'
    },
  },

  // ------------------------------------------------ 学生任务与课堂（M3）
  {
    path: '/student',
    redirect: '/student/tasks',
    meta: { requiresAuth: true, roles: ['student'] },
  },
  {
    path: '/student/tasks',
    name: 'student-tasks',
    component: () => import('../views/task/StudentTasksPage.vue'),
    meta: { requiresAuth: true, roles: ['student'], layout: 'app', title: '我的任务' },
  },
  {
    path: '/student/tasks/:taskId',
    name: 'student-task-detail',
    component: () => import('../views/task/StudentTaskDetailPage.vue'),
    meta: { requiresAuth: true, roles: ['student'], layout: 'app', title: '任务详情' },
  },
  {
    path: '/student/sessions/:sessionId/classroom',
    name: 'student-classroom',
    component: () => import('../views/classroom/ClassroomPage.vue'),
    meta: { requiresAuth: true, roles: ['student'], layout: 'immersive', title: '课堂试讲' },
  },
  {
    path: '/student/sessions/:sessionId/interview',
    name: 'student-interview',
    component: () => import('../views/classroom/InterviewPage.vue'),
    meta: { requiresAuth: true, roles: ['student'], layout: 'immersive', title: '结构化面试' },
  },
  {
    path: '/student/sessions/:sessionId/review',
    name: 'student-review',
    component: () => import('../views/replay/ReviewPage.vue'),
    meta: { requiresAuth: true, roles: ['student'], layout: 'immersive', title: '回看与提交' },
  },
  {
    path: '/student/results',
    name: 'student-results',
    component: () => import('../views/grading/StudentResultsPage.vue'),
    meta: { requiresAuth: true, roles: ['student'], layout: 'app', title: '我的成绩' },
  },
  {
    path: '/student/results/:submissionId',
    name: 'student-result-detail',
    component: () => import('../views/grading/StudentResultDetailPage.vue'),
    meta: { requiresAuth: true, roles: ['student'], layout: 'app', title: '成绩详情' },
  },

  // ------------------------------------------------ 教师任务（M3 教师端）
  {
    path: '/teacher',
    redirect: '/courses',
    meta: { requiresAuth: true, roles: ['teacher'] },
  },
  {
    path: '/teacher/courses/:courseId/tasks',
    name: 'teacher-course-tasks',
    component: () => import('../views/task/TeacherTaskListPage.vue'),
    meta: {
      requiresAuth: true,
      roles: ['teacher'],
      layout: 'app',
      title: '课程任务',
    },
  },
  {
    path: '/teacher/courses/:courseId/tasks/new',
    name: 'teacher-task-new',
    component: () => import('../views/task/TeacherTaskEditorPage.vue'),
    meta: {
      requiresAuth: true,
      roles: ['teacher'],
      layout: 'app',
      title: '新建任务',
    },
  },
  {
    path: '/teacher/courses/:courseId/tasks/:taskId',
    name: 'teacher-task-detail',
    component: () => import('../views/task/TeacherTaskEditorPage.vue'),
    meta: {
      requiresAuth: true,
      roles: ['teacher'],
      layout: 'app',
      title: '任务详情',
    },
  },
  {
    path: '/teacher/courses/:courseId/tasks/:taskId/edit',
    redirect: (to) => ({
      name: 'teacher-task-detail',
      params: { courseId: to.params.courseId, taskId: to.params.taskId },
    }),
  },

  // ------------------------------------------------ 教师评分 / 统计（M7）
  {
    path: '/teacher/courses/:courseId/tasks/:taskId/grading',
    name: 'task-grading',
    component: () => import('../views/grading/TaskGradingPage.vue'),
    meta: {
      requiresAuth: true,
      roles: ['teacher', 'admin'],
      layout: 'app',
      title: '任务评分',
    },
  },
  {
    path: '/teacher/courses/:courseId/tasks/:taskId/stats',
    name: 'task-stats',
    component: () => import('../views/stats/TaskStatsPage.vue'),
    meta: {
      requiresAuth: true,
      roles: ['teacher', 'admin'],
      layout: 'app',
      title: '任务统计',
    },
  },
  {
    path: '/teacher/courses/:courseId/stats',
    name: 'course-stats',
    component: () => import('../views/stats/CourseStatsPage.vue'),
    meta: {
      requiresAuth: true,
      roles: ['teacher', 'admin'],
      layout: 'app',
      title: '课程统计',
    },
  },

  // ------------------------------------------------ M1 账号 / 组织 / 认证
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/auth/LoginView.vue'),
    meta: { layout: 'auth', title: '登录' },
  },
  {
    path: '/register',
    name: 'register',
    component: () => import('../views/auth/RegisterView.vue'),
    meta: { layout: 'auth', title: '注册' },
  },
  {
    path: '/register/oauth',
    name: 'register-oauth',
    component: () => import('../views/auth/OAuthRegisterView.vue'),
    meta: { layout: 'auth', title: '微信注册' },
  },
  {
    path: '/settings/security',
    name: 'settings-security',
    component: () => import('../views/admin/SecurityView.vue'),
    meta: { requiresAuth: true, layout: 'app', title: '账号安全' },
  },
  {
    path: '/admin/setup',
    name: 'admin-setup',
    component: () => import('../views/admin/SetupView.vue'),
    meta: {
      requiresAuth: true,
      adminOnly: true,
      layout: 'app',
      title: '创建学校',
    },
  },
  {
    path: '/admin/org',
    name: 'admin-org',
    component: () => import('../views/admin/OrgView.vue'),
    meta: {
      requiresAuth: true,
      adminOnly: true,
      requiresSchool: true,
      layout: 'app',
      title: '学校与组织',
    },
  },
  {
    path: '/admin/teachers',
    name: 'admin-teachers',
    component: () => import('../views/admin/TeachersView.vue'),
    meta: {
      requiresAuth: true,
      adminOnly: true,
      requiresSchool: true,
      layout: 'app',
      title: '教师管理',
    },
  },
  {
    path: '/admin',
    redirect: '/admin/org',
  },
  {
    path: '/admin/courses',
    redirect: '/courses',
  },

  // ------------------------------------------------ M2 课程
  ...courseRoutes,

  // ------------------------------------------------ M4 评测报告（共享终点）
  {
    path: '/evaluations/:evaluationId/report',
    name: 'evaluation-report',
    component: () => import('../views/report/EvaluationReportPage.vue'),
    meta: { requiresAuth: true, layout: 'app', title: 'AI 评测报告' },
  },
  {
    path: '/submissions/:submissionId/report',
    name: 'submission-report',
    component: () => import('../views/report/SubmissionReportPage.vue'),
    meta: { requiresAuth: true, layout: 'app', title: 'AI 评测报告' },
  },
  {
    path: '/student/training/records/:recordId/report',
    name: 'training-record-report',
    component: () => import('../views/report/TrainingRecordReportPage.vue'),
    meta: {
      requiresAuth: true,
      roles: ['student'],
      layout: 'app',
      title: '训练评测报告',
    },
  },
  {
    path: '/training/records/:recordId/report',
    redirect: (to) => ({
      name: 'training-record-report',
      params: { recordId: to.params.recordId },
    }),
  },

  // ------------------------------------------------ M5 题库 / M6 训练
  ...questionRoutes,
  ...trainingRoutes,

  // ------------------------------------------------ 文档路径兼容
  {
    path: '/question-bank',
    redirect: '/teacher/question-bank',
  },
  {
    path: '/teacher/training-templates',
    redirect: '/teacher/training/templates',
  },
  {
    path: '/teacher/evaluations/:evaluationId',
    redirect: (to) => ({
      name: 'evaluation-report',
      params: { evaluationId: to.params.evaluationId },
    }),
  },

  // ------------------------------------------------ 错误页
  {
    path: '/403',
    name: 'forbidden',
    component: () => import('../views/errors/ForbiddenPage.vue'),
    meta: { layout: 'blank', title: '无权限' },
  },
  {
    path: '/404',
    component: () => import('../views/errors/NotFoundPage.vue'),
    meta: { layout: 'blank', title: '页面不存在' },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('../views/errors/NotFoundPage.vue'),
    meta: { layout: 'blank', title: '页面不存在' },
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
 * - `meta.roles` 不匹配 → 403
 * - 无 admin 角色访问管理端 → 403
 * - 管理员角色但未建校 → /admin/setup
 * - 已登录再访问登录 / 注册页 → 回各自首页
 */
router.beforeEach((to) => {
  const claims = decodeJwt(getToken())

  if (to.meta.requiresAuth && !claims) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.meta.roles && to.meta.roles.length > 0
      && !to.meta.roles.some((role) => hasRole(claims, role))) {
    return { path: '/403' }
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

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · 三尺讲台` : '三尺讲台'
})
