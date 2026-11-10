<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElButton, ElTag } from 'element-plus'
import { useAuthStore } from '../../stores/auth'

/**
 * Small M7 page header. The repo does not yet have the shared AppShell from
 * 09 §7, so M7 pages carry their own compact header and dev role switch.
 */

const props = defineProps<{
  title: string
  subtitle?: string
  courseId?: string
  taskId?: string
}>()

const auth = useAuthStore()
const router = useRouter()

const roleLabel = computed(() => (auth.isTeacher ? '教师视角' : '学生视角'))

function switchRole(role: 'student' | 'teacher'): void {
  if (auth.role === role) {
    return
  }
  auth.setDevIdentity(role)
  if (role === 'student') {
    if (window.location.pathname.startsWith('/student/')) {
      window.location.reload()
      return
    }
    void router.push('/student/results')
    return
  }
  window.location.reload()
}
</script>

<template>
  <header class="m7-header">
    <div class="m7-header__main">
      <div>
        <h1 class="m7-header__title">{{ title }}</h1>
        <p v-if="subtitle" class="m7-header__subtitle">{{ subtitle }}</p>
      </div>
      <div class="m7-header__role">
        <ElTag size="small" type="info">{{ roleLabel }}</ElTag>
        <ElButton
          size="small"
          :type="auth.isTeacher ? 'default' : 'primary'"
          @click="switchRole('teacher')"
        >
          教师
        </ElButton>
        <ElButton
          size="small"
          :type="auth.isStudent ? 'default' : 'primary'"
          @click="switchRole('student')"
        >
          学生
        </ElButton>
      </div>
    </div>
    <nav class="m7-header__nav">
      <router-link v-if="auth.isTeacher && courseId && taskId" :to="`/teacher/courses/${courseId}/tasks/${taskId}/grading`">
        任务评分
      </router-link>
      <router-link v-if="auth.isTeacher && courseId && taskId" :to="`/teacher/courses/${courseId}/tasks/${taskId}/stats`">
        任务统计
      </router-link>
      <router-link v-if="auth.isTeacher && courseId" :to="`/teacher/courses/${courseId}/stats`">
        课程统计
      </router-link>
      <router-link v-if="auth.isStudent" to="/student/results">我的成绩</router-link>
    </nav>
  </header>
</template>

<style scoped>
.m7-header {
  margin-bottom: 16px;
  padding: 16px 20px;
  border: 1px solid #1f2b44;
  border-radius: 12px;
  background: #111c33;
}

.m7-header__main {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.m7-header__title {
  margin: 0;
  color: #e8eef9;
  font-size: 22px;
}

.m7-header__subtitle {
  margin: 6px 0 0;
  color: #8fa3c0;
  font-size: 13px;
}

.m7-header__role {
  display: flex;
  align-items: center;
  gap: 8px;
}

.m7-header__nav {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid #1f2b44;
  font-size: 13px;
}

.m7-header__nav a {
  color: #8fa3c0;
  text-decoration: none;
}

.m7-header__nav a.router-link-active {
  color: #f5a524;
  font-weight: 600;
}
</style>
