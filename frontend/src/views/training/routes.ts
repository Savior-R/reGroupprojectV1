import type { RouteRecordRaw } from 'vue-router'

/** M6 training routes (08 §12.1). Merged into the shared router (router.ts owner). */
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
    path: '/student/training/templates/:publicationId',
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
    path: '/student/training/my-templates/new',
    name: 'student-training-template-new',
    component: () => import('./TrainingTemplateEditorPage.vue'),
    meta: {
      requiresAuth: true,
      roles: ['student'],
      layout: 'app',
      title: '新建训练模板',
    },
  },
  {
    path: '/student/training/my-templates/:templateId/edit',
    name: 'student-training-template-edit',
    component: () => import('./TrainingTemplateEditorPage.vue'),
    meta: {
      requiresAuth: true,
      roles: ['student'],
      layout: 'app',
      title: '编辑训练模板',
    },
  },
  {
    path: '/student/training/config',
    name: 'student-training-config',
    component: () => import('./TrainingConfigPage.vue'),
    meta: {
      requiresAuth: true,
      roles: ['student'],
      layout: 'app',
      title: '训练配置',
    },
  },
  {
    path: '/student/training/free',
    name: 'student-training-free',
    redirect: (to) => ({
      name: 'student-training-config',
      query: {
        mode: 'free',
        ...(to.query.level ? { level: String(to.query.level) } : {}),
      },
    }),
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
  {
    path: '/teacher/training/templates/new',
    name: 'teacher-training-template-new',
    component: () => import('./TrainingTemplateEditorPage.vue'),
    meta: {
      requiresAuth: true,
      roles: ['teacher'],
      layout: 'app',
      title: '新建训练模板',
    },
  },
  {
    path: '/teacher/training/templates/:templateId',
    name: 'teacher-training-template-detail',
    component: () => import('./TrainingTemplateEditorPage.vue'),
    meta: {
      requiresAuth: true,
      roles: ['teacher'],
      layout: 'app',
      title: '训练模板',
    },
  },
]
