import type { RouteRecordRaw } from 'vue-router'

/** M5 question bank routes. Merged into the shared router (router.ts owner). */
export const questionRoutes: RouteRecordRaw[] = [
  {
    path: '/teacher/question-bank',
    name: 'teacher-question-bank',
    component: () => import('./QuestionBankPage.vue'),
    meta: {
      requiresAuth: true,
      roles: ['teacher', 'admin'],
      layout: 'app',
      title: '结构化面试题库',
    },
  },
  {
    path: '/teacher/question-bank/categories',
    name: 'teacher-question-bank-categories',
    component: () => import('./CategoryManagementPage.vue'),
    meta: {
      requiresAuth: true,
      roles: ['teacher', 'admin'],
      layout: 'app',
      title: '题库类别管理',
    },
  },
]
