<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElButton, ElTag } from 'element-plus'
import { me, setToken } from '../api/auth'
import type { AccountView } from '../api/auth'
import { getToken } from '../api/auth'
import { decodeJwt, hasRole, isAdmin, rolesOf } from '../api/jwt'
import type { Role } from '../api/jwt'

/**
 * 全站应用外壳（09 §3）。
 *
 * 登录后的普通页面统一套这一层：品牌、按角色裁剪的主导航、账号信息和退出。
 * 课堂 / 面试 / 回看 / 训练房间等沉浸式页面不套壳，继续使用 ImmersiveLayout。
 *
 * 导航按 JWT 中的 roles 取并集，多角色账号（例如教师兼管理员）同时看到
 * 教师与管理员入口，而不是只显示主身份。
 */

interface NavItem {
  path: string
  label: string
}

const route = useRoute()
const router = useRouter()

const claims = computed(() => decodeJwt(getToken()))
const account = ref<AccountView | null>(null)

const isAdminUser = computed(() => isAdmin(claims.value))
const isTeacherUser = computed(() => hasRole(claims.value, 'teacher'))
const isStudentUser = computed(() => hasRole(claims.value, 'student'))
const hasSchool = computed(() => Boolean(claims.value?.school_id))

const navItems = computed<NavItem[]>(() => {
  // 未建校管理员只保留建校入口，避免点进其他管理页后被守卫拦成 403。
  if (isAdminUser.value && !hasSchool.value) {
    return [{ path: '/admin/setup', label: '创建学校' }]
  }

  const items: NavItem[] = []
  const add = (path: string, label: string, roles: Role[]) => {
    if (roles.some((role) => rolesOf(claims.value).includes(role))) {
      items.push({ path, label })
    }
  }

  add('/courses', isAdminUser.value && !isTeacherUser.value && !isStudentUser.value ? '课程管理' : '课程', [
    'student',
    'teacher',
    'admin',
  ])

  if (isStudentUser.value) {
    add('/student/tasks', '任务', ['student'])
    add('/student/training', '训练', ['student'])
    add('/student/results', '我的成绩', ['student'])
  }

  if (isTeacherUser.value) {
    add('/teacher/question-bank', '题库', ['teacher'])
    add('/teacher/training/templates', '训练模板', ['teacher'])
  }

  if (isAdminUser.value) {
    add('/admin/org', '学校与组织', ['admin'])
    add('/admin/teachers', '教师管理', ['admin'])
  }

  // 多角色并集去重，避免教师兼管理员看到两个「课程」。
  return items.filter((item, index) => items.findIndex((candidate) => candidate.path === item.path) === index)
})

const typeLabel = computed(() => {
  const names: Record<Role, string> = { admin: '管理员', teacher: '教师', student: '学生' }
  return rolesOf(claims.value).map((role) => names[role] ?? role).join(' / ') || '未登录'
})

function isActive(path: string): boolean {
  return route.path === path || route.path.startsWith(`${path}/`)
}

onMounted(async () => {
  account.value = await me().catch(() => null)
})

function logout(): void {
  setToken(null)
  void router.push('/login')
}
</script>

<template>
  <div class="app-shell">
    <header class="app-shell__bar">
      <router-link to="/" class="app-shell__brand">三尺讲台</router-link>

      <nav class="app-shell__nav" aria-label="主导航">
        <router-link
          v-for="item in navItems"
          :key="item.path"
          :to="item.path"
          class="app-shell__nav-item"
          :class="{ 'app-shell__nav-item--active': isActive(item.path) }"
        >
          {{ item.label }}
        </router-link>
      </nav>

      <div class="app-shell__user">
        <ElTag size="small" type="info">{{ typeLabel }}</ElTag>
        <span class="app-shell__name">{{ account?.username ?? '' }}</span>
        <router-link to="/settings/security" class="app-shell__account-link">账号安全</router-link>
        <ElButton link type="primary" size="small" @click="logout">退出登录</ElButton>
      </div>
    </header>

    <main class="app-shell__content">
      <slot>
        <router-view />
      </slot>
    </main>
  </div>
</template>

<style scoped>
.app-shell {
  min-height: 100vh;
  background: var(--m1-bg-page);
  color: var(--m1-text-regular);
}

.app-shell__bar {
  position: sticky;
  top: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  gap: 24px;
  height: 60px;
  padding: 0 28px;
  background: rgba(255, 255, 255, 0.94);
  border-bottom: 1px solid var(--m1-border);
  backdrop-filter: blur(10px);
}

.app-shell__brand {
  flex: none;
  color: var(--m1-text-primary);
  font-family: var(--m1-font-serif);
  font-size: 18px;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-decoration: none;
  white-space: nowrap;
}

.app-shell__nav {
  display: flex;
  flex: 1;
  gap: 2px;
  min-width: 0;
  overflow-x: auto;
}

.app-shell__nav-item {
  position: relative;
  padding: 8px 12px;
  border-radius: var(--m1-radius-sm);
  color: var(--m1-text-regular);
  font-size: 14px;
  text-decoration: none;
  white-space: nowrap;
}

.app-shell__nav-item:hover {
  background: var(--m1-bg-subtle);
  color: var(--m1-text-primary);
}

.app-shell__nav-item--active {
  color: var(--m1-primary);
  background: var(--m1-primary-soft);
  font-weight: 600;
}

.app-shell__nav-item--active::after {
  position: absolute;
  right: 12px;
  bottom: 3px;
  left: 12px;
  height: 2px;
  border-radius: 2px;
  background: var(--m1-primary);
  content: '';
}

.app-shell__user {
  display: flex;
  flex: none;
  align-items: center;
  gap: 10px;
  white-space: nowrap;
}

.app-shell__name {
  color: var(--m1-text-regular);
  font-size: 14px;
}

.app-shell__account-link {
  color: var(--m1-text-secondary);
  font-size: 13px;
  text-decoration: none;
}

.app-shell__account-link:hover {
  color: var(--m1-primary);
}

.app-shell__content {
  min-height: calc(100vh - 60px);
}
</style>
