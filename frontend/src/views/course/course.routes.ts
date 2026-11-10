import type { RouteRecordRaw } from 'vue-router'

/**
 * M2 课程路由（设计 03 §4.7）。
 *
 * 由 `app/router.ts` 挂载；页面与路由都在 M2 owner 的路径范围内，
 * 共享路由文件只做一次展开，避免其他模块改动课程路由。
 *
 * M1 已切到 JWT，四条路由都标记 `requiresAuth`，未登录跳登录页。
 */
export const courseRoutes: RouteRecordRaw[] = [
  {
    path: '/courses',
    name: 'courses',
    component: () => import('./CourseListPage.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/courses/:courseId',
    name: 'course-detail',
    component: () => import('./CourseDetailPage.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/courses/:courseId/teachers',
    name: 'course-teachers',
    component: () => import('./CourseTeachersPage.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/courses/:courseId/students',
    name: 'course-students',
    component: () => import('./CourseStudentsPage.vue'),
    meta: { requiresAuth: true },
  },
]
