import type { RouteRecordRaw } from 'vue-router'

/** M6 training routes. Merged into the shared router (router.ts owner). */
export const trainingRoutes: RouteRecordRaw[] = [
  {
    path: '/student/training',
    name: 'student-training',
    component: () => import('./TrainingHomePage.vue'),
    meta: {
      requiresAuth: true,
      roles: ['student'],
      layout: 'app',
      title: '训练',
    },
  },
  {
    path: '/student/training/templates/:templateId',
    name: 'student-training-template',
    component: () => import('./TemplateTrainingConfigPage.vue'),
    meta: {
      requiresAuth: true,
      roles: ['student'],
      layout: 'app',
      title: '模板训练配置',
    },
  },
  {
    path: '/student/training/free',
    name: 'student-training-free',
    component: () => import('./FreeTrainingConfigPage.vue'),
    meta: {
      requiresAuth: true,
      roles: ['student'],
      layout: 'app',
      title: '自由训练配置',
    },
  },
  {
    path: '/student/training/sessions/:sessionId',
    redirect: (to) => ({
      name: 'student-training-classroom',
      params: { sessionId: to.params.sessionId },
    }),
  },
  {
    path: '/student/training/sessions/:sessionId/classroom',
    name: 'student-training-classroom',
    component: () => import('../classroom/ClassroomPage.vue'),
    meta: {
      requiresAuth: true,
      roles: ['student'],
      layout: 'immersive',
      title: '训练课堂',
    },
  },
  {
    path: '/student/training/sessions/:sessionId/review',
    name: 'student-training-review',
    component: () => import('./TrainingReviewPage.vue'),
    meta: {
      requiresAuth: true,
      roles: ['student'],
      layout: 'immersive',
      title: '训练回看',
    },
  },
  {
    path: '/student/training/records',
    name: 'student-training-records',
    component: () => import('./TrainingRecordsPage.vue'),
    meta: {
      requiresAuth: true,
      roles: ['student'],
      layout: 'app',
      title: '训练记录',
    },
  },
  {
    path: '/student/training/records/:recordId',
    name: 'student-training-record-detail',
    component: () => import('./TrainingRecordDetailPage.vue'),
    meta: {
      requiresAuth: true,
      roles: ['student'],
      layout: 'app',
      title: '训练记录详情',
    },
  },
  {
    path: '/teacher/training/templates',
    name: 'teacher-training-templates',
    component: () => import('./TeacherTrainingTemplatesPage.vue'),
    meta: {
      requiresAuth: true,
      roles: ['teacher'],
      layout: 'app',
      title: '训练模板',
    },
  },
]
