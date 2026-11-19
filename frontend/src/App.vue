<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AppShell from './layouts/AppShell.vue'

/**
 * 全站页面布局选择（09 §4.2）。
 *
 * `meta.layout = 'app'` 的页面统一套 AppShell；`auth` / `blank` 保持裸布局；
 * `immersive` 页面自己使用 ImmersiveLayout。
 */

const route = useRoute()
const layout = computed(() => route.meta.layout ?? 'app')
</script>

<template>
  <AppShell v-if="layout === 'app'">
    <router-view />
  </AppShell>
  <router-view v-else />
</template>
