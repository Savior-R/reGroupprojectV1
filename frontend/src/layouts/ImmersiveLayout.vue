<script setup lang="ts">
import { ElButton, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'

/**
 * Immersive shell for classroom / interview / replay pages (09 §3.5).
 *
 * Top bar: back, title, status slot, actions slot and an exit button that asks
 * for confirmation before the page ends the session.
 */

const props = withDefaults(
  defineProps<{
    title: string
    /** Navigate here after emitting `back`; omit to let the page handle it. */
    backTo?: string
    exitConfirm?: boolean
    exitConfirmText?: string
  }>(),
  {
    exitConfirm: true,
    exitConfirmText: '退出后本次试讲将结束，确定退出吗？',
  },
)

const emit = defineEmits<{
  back: []
  exit: []
}>()

const router = useRouter()

function goBack(): void {
  emit('back')
  if (props.backTo) {
    void router.push(props.backTo)
  }
}

async function requestExit(): Promise<void> {
  if (!props.exitConfirm) {
    emit('exit')
    return
  }
  try {
    await ElMessageBox.confirm(props.exitConfirmText, '退出确认', {
      confirmButtonText: '退出',
      cancelButtonText: '继续',
      type: 'warning',
    })
    emit('exit')
  } catch {
    // The user cancelled; stay in the session.
  }
}
</script>

<template>
  <div class="immersive-layout">
    <header class="immersive-layout__bar">
      <ElButton text class="immersive-layout__back" @click="goBack">← 返回</ElButton>
      <h1 class="immersive-layout__title">{{ title }}</h1>
      <div class="immersive-layout__status">
        <slot name="status" />
      </div>
      <div class="immersive-layout__actions">
        <slot name="actions" />
      </div>
      <ElButton text type="danger" class="immersive-layout__exit" @click="requestExit">
        退出
      </ElButton>
    </header>
    <main class="immersive-layout__content">
      <slot />
    </main>
  </div>
</template>

<style scoped>
.immersive-layout {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  background: #0b1220;
  color: #e5e7eb;
}

.immersive-layout__bar {
  display: flex;
  align-items: center;
  gap: 16px;
  height: 56px;
  padding: 0 20px;
  border-bottom: 1px solid rgba(148, 163, 184, 0.24);
  background: rgba(11, 18, 32, 0.94);
  backdrop-filter: blur(8px);
  flex: none;
}

.immersive-layout__back,
.immersive-layout__exit {
  flex: none;
}

.immersive-layout__title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.immersive-layout__status {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-left: 8px;
  min-width: 0;
}

.immersive-layout__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
}

.immersive-layout__content {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
</style>