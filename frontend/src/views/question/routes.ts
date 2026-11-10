import type { RouteRecordRaw } from 'vue-router'

/** M5 question bank routes. Merge into the shared router (router.ts owner). */
export const questionRoutes: RouteRecordRaw[] = [
  {
    path: '/teacher/question-bank',
    name: 'teacher-question-bank',
    component: () => import('./QuestionBankPage.vue'),
  },
]
