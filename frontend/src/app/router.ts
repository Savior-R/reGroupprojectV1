import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'

/**
 * Minimal router shell for the M3 frontend slice (09 §4.1).
 *
 * Only the root redirect and the shared error pages live here in Task 1.
 * Tasks 2/5/6/7 append the task, classroom, interview and review routes.
 */

export const routes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: '/student/tasks',
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