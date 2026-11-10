<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getToken, me, setToken } from '../../api/auth'
import type { AccountView } from '../../api/auth'
import { decodeJwt, rolesOf } from '../../api/jwt'

/**
 * M1 管理端外壳（`views/admin/**` 内，不占用 Score/QA 的 `layouts/**`）。
 *
 * <p>解决两个实际体验问题：
 * <ul>
 *   <li>此前已登录访问管理页看不到任何导航，只能手敲 URL 跳转
 *   <li>「退出登录」原先只存在于 `/settings/security`，管理员从 `/admin/org` 无法退出
 * </ul>
 */

const route = useRoute()
const router = useRouter()

const claims = computed(() => decodeJwt(getToken()))
const account = ref<AccountView | null>(null)

const hasSchool = computed(() => Boolean(claims.value?.school_id))

const links = computed(() =>
  hasSchool.value
    ? [
        { path: '/admin/org', label: '学校与组织' },
        { path: '/admin/teachers', label: '教师管理' },
        { path: '/settings/security', label: '账号安全' },
      ]
    : [{ path: '/admin/setup', label: '创建学校' }],
)

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
      <div class="brand">三尺讲台 · 管理端</div>
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
      <slot />
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
