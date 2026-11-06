import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'

/**
 * Minimal router shell for the M3 frontend slice (09 §4.1).
 *
 * Task 1 added the root redirect and the shared error pages; Task 2 adds the
 * student task list/detail routes. Tasks 5/6 add the classroom and interview
 * routes; Task 7 adds the review/replay route.
 */

export const routes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: '/student/tasks',
  },
  {
    path: '/student/tasks',
    name: 'student-tasks',
    component: () => import('../views/task/StudentTasksPage.vue'),
  },
  {
    path: '/student/tasks/:taskId',
    name: 'student-task-detail',
    component: () => import('../views/task/StudentTaskDetailPage.vue'),
  },
  {
    path: '/student/sessions/:sessionId/classroom',
    name: 'student-classroom',
    component: () => import('../views/classroom/ClassroomPage.vue'),
  },
  {
    path: '/student/sessions/:sessionId/interview',
    name: 'student-interview',
    component: () => import('../views/classroom/InterviewPage.vue'),
  },
  {
    path: '/student/sessions/:sessionId/review',
    name: 'student-review',
    component: () => import('../views/replay/ReviewPage.vue'),
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