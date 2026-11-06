<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ElAlert, ElButton } from 'element-plus'
import ReplayPlayer from '../../components/replay/ReplayPlayer.vue'
import { getEvents, getReplay } from '../../api/replay'
import type { ReplayEvent } from '../../api/replay'
import type { ReplayView } from '../../types/recording'

/**
 * Read-only replay surface (05B §7.3, 09 §10.4).
 *
 * Loads the replay manifest + event history and renders the synchronized
 * player. The review page embeds it while the session is still submittable;
 * after submission the same component is reused as the read-only replay.
 * `reload()` refetches the manifest (fresh 15-minute presigned URLs).
 */

const props = withDefaults(
  defineProps<{
    sessionId: string
    initialTimeMs?: number
  }>(),
  {
    initialTimeMs: 0,
  },
)

const replay = ref<ReplayView | null>(null)
const events = ref<ReplayEvent[]>([])
const loading = ref(true)
const error = ref('')
let disposed = false

function messageOf(value: unknown, fallback: string): string {
  return value instanceof Error && value.message ? value.message : fallback
}

async function reload(): Promise<void> {
  if (!props.sessionId || disposed) {
    return
  }
  loading.value = true
  error.value = ''
  try {
    const [manifest, eventPage] = await Promise.all([
      getReplay(props.sessionId),
      getEvents(props.sessionId, 0, 100),
    ])
    if (disposed) {
      return
    }
    replay.value = manifest
    events.value = eventPage.items
  } catch (value) {
    if (!disposed) {
      error.value = messageOf(value, '回放加载失败，请重试。')
    }
  } finally {
    if (!disposed) {
      loading.value = false
    }
  }
}

defineExpose({ reload })

onMounted(() => {
  void reload()
})

onBeforeUnmount(() => {
  disposed = true
})
</script>

<template>
  <div class="replay-page" data-testid="replay-page">
    <p v-if="loading && !replay" class="replay-page__state" data-testid="replay-loading">
      回放加载中…
    </p>

    <ElAlert
      v-else-if="error && !replay"
      class="replay-page__state"
      type="error"
      :closable="false"
      show-icon
      data-testid="replay-load-error"
    >
      <template #title>{{ error }}</template>
      <ElButton size="small" data-testid="replay-retry" @click="reload">重试</ElButton>
    </ElAlert>

    <template v-else-if="replay">
      <ElAlert
        v-if="error"
        class="replay-page__state"
        type="warning"
        :closable="false"
        show-icon
        data-testid="replay-refresh-error"
      >
        <template #title>{{ error }}</template>
      </ElAlert>
      <ReplayPlayer
        :replay="replay"
        :events="events"
        :initial-time-ms="initialTimeMs"
        @refresh="reload"
      />
    </template>
  </div>
</template>

<style scoped>
.replay-page {
  display: flex;
  min-height: 0;
  flex: 1;
  flex-direction: column;
  gap: 10px;
}

.replay-page__state {
  margin: 12px 0;
}
</style>