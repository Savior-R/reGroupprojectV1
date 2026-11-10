<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getToken, me, setToken } from '../../api/auth'
import type { AccountView } from '../../api/auth'
import { decodeJwt, hasRole, isAdmin, rolesOf } from '../../api/jwt'

/**
 * 应用外壳（放在 `views/admin/**` 内，不占用 Score/QA 的 `layouts/**`）。
 *
 * <p>导航必须按**角色**裁剪，不能只看有没有学校：学生 / 教师注册时同样绑定了
 * 学校，早期实现因此把「学校与组织」「教师管理」显示给了他们，点进去又被
 * 路由守卫拦成 403。
 *
 * <ul>
 *   <li>管理员（含被授予 admin 的教师）：学校与组织、教师管理
 *   <li>未建校管理员：创建学校
 *   <li>学生：任务中心
 *   <li>所有人：账号安全 + 退出登录
 * </ul>
 */

const route = useRoute()
const router = useRouter()

const claims = computed(() => decodeJwt(getToken()))
const account = ref<AccountView | null>(null)

const isAdminUser = computed(() => isAdmin(claims.value))
const isStudentUser = computed(() => hasRole(claims.value, 'student'))
const hasSchool = computed(() => Boolean(claims.value?.school_id))

interface NavItem {
  path: string
  label: string
}

const links = computed<NavItem[]>(() => {
  const items: NavItem[] = []

  if (isAdminUser.value) {
    if (hasSchool.value) {
      items.push({ path: '/admin/org', label: '学校与组织' })
      items.push({ path: '/admin/teachers', label: '教师管理' })
    } else {
      items.push({ path: '/admin/setup', label: '创建学校' })
    }
  }

  // 任务中心目前只实现学生端；教师端页面由 M3 提供，未就绪前不放入口，
  // 否则点进去会被后端 requireStudent 拒成 403
  if (isStudentUser.value) {
    items.push({ path: '/student/tasks', label: '任务中心' })
  }

  items.push({ path: '/settings/security', label: '账号安全' })
  return items
})

const brand = computed(() => (isAdminUser.value ? '三尺讲台 · 管理端' : '三尺讲台'))

/** 主身份 + 附加角色：教师兼管理员显示「教师 / 管理员」。 */
const typeLabel = computed(() => {
  const roles = rolesOf(claims.value)
  if (roles.length === 0) {
    return '未登录'
  }
  const names: Record<string, string> = { admin: '管理员', teacher: '教师', student: '学生' }
  return roles.map((role) => names[role] ?? role).join(' / ')
})

onMounted(async () => {
  account.value = await me().catch(() => null)
})

function logout(): void {
  setToken(null)
  ElMessage.success('已退出登录')
  void router.push('/login')
}
</script>

<template>
  <div class="shell">
    <header class="topbar">
      <div class="brand">{{ brand }}</div>
      <nav class="nav">
        <router-link
          v-for="item in links"
          :key="item.path"
          :to="item.path"
          class="nav-item"
          :class="{ active: route.path === item.path }"
        >
          {{ item.label }}
        </router-link>
      </nav>
      <div class="user">
        <el-tag size="small" type="info">{{ typeLabel }}</el-tag>
        <span class="name">{{ account?.username ?? '' }}</span>
        <el-button link type="primary" size="small" @click="logout">退出登录</el-button>
      </div>
    </header>
    <main class="content">
      <!-- 两种用法：作为页面包裹组件时用 slot；作为父级路由布局时渲染 router-view -->
      <slot>
        <router-view />
      </slot>
    </main>
  </div>
</template>

<style scoped>
.shell {
  min-height: 100vh;
  background: var(--el-bg-color-page);
}

.topbar {
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 0 24px;
  height: 56px;
  background: var(--el-bg-color);
  border-bottom: 1px solid var(--el-border-color-light);
  position: sticky;
  top: 0;
  z-index: 10;
}

.brand {
  font-weight: 600;
  color: var(--el-text-color-primary);
  white-space: nowrap;
}

.nav {
  display: flex;
  gap: 4px;
  flex: 1;
}

.nav-item {
  padding: 6px 12px;
  border-radius: 6px;
  font-size: 14px;
  color: var(--el-text-color-regular);
  text-decoration: none;
}

.nav-item:hover {
  background: var(--el-fill-color-light);
}

.nav-item.active {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.user {
  display: flex;
  align-items: center;
  gap: 8px;
  white-space: nowrap;
}

.name {
  font-size: 14px;
  color: var(--el-text-color-regular);
}

.content {
  min-height: calc(100vh - 56px);
}
</style>
