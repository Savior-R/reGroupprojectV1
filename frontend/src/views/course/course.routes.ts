import type { RouteRecordRaw } from 'vue-router'

/**
 * M2 课程路由（设计 03 §4.7）。
 *
 * 由 `app/router.ts` 挂载；页面与路由都在 M2 owner 的路径范围内，
 * 共享路由文件只做一次展开，避免其他模块改动课程路由。
 *
 * 登录后统一走 AppShell；课程对所有角色可见，写操作由页面按钮和后端权限裁剪。
 */
export const courseRoutes: RouteRecordRaw[] = [
  {
    path: '/courses',
    name: 'courses',
    component: () => import('./CourseListPage.vue'),
    meta: { requiresAuth: true, layout: 'app', title: '课程' },
  },
  {
    path: '/courses/:courseId',
    name: 'course-detail',
    component: () => import('./CourseDetailPage.vue'),
    meta: { requiresAuth: true, layout: 'app', title: '课程详情' },
  },
  {
    path: '/courses/:courseId/teachers',
    name: 'course-teachers',
    component: () => import('./CourseTeachersPage.vue'),
    meta: { requiresAuth: true, layout: 'app', title: '课程老师组' },
  },
  {
    path: '/courses/:courseId/students',
    name: 'course-students',
    component: () => import('./CourseStudentsPage.vue'),
    meta: { requiresAuth: true, layout: 'app', title: '课程学生' },
  },
]
