import type { RouteRecordRaw } from 'vue-router'

/** M6 training routes. Merge into the shared router (router.ts owner). */
export const trainingRoutes: RouteRecordRaw[] = [
  {
    path: '/student/training',
    name: 'student-training',
    component: () => import('./TrainingHomePage.vue'),
  },
  {
    path: '/student/training/templates/:templateId',
    name: 'student-training-template',
    component: () => import('./TemplateTrainingConfigPage.vue'),
  },
  {
    path: '/student/training/free',
    name: 'student-training-free',
    component: () => import('./FreeTrainingConfigPage.vue'),
  },
  {
    path: '/student/training/sessions/:sessionId',
    name: 'student-training-room',
    component: () => import('./TrainingRoomPage.vue'),
  },
  {
    path: '/student/training/sessions/:sessionId/review',
    name: 'student-training-review',
    component: () => import('./TrainingReviewPage.vue'),
  },
  {
    path: '/student/training/records',
    name: 'student-training-records',
    component: () => import('./TrainingRecordsPage.vue'),
  },
  {
    path: '/student/training/records/:recordId',
    name: 'student-training-record-detail',
    component: () => import('./TrainingRecordDetailPage.vue'),
  },
  {
    path: '/teacher/training/templates',
    name: 'teacher-training-templates',
    component: () => import('./TeacherTrainingTemplatesPage.vue'),
  },
]
