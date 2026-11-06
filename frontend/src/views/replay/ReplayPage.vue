<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ElAlert, ElButton } from 'element-plus'
import ReplayPlayer from '../../components/replay/ReplayPlayer.vue'
import { getEvents, getReplay } from '../../api/replay'
import type { ReplayEvent } from '../../api/replay'
import type { ReplayView } from '../../types/recording'
import { messageOf } from '../../utils/message'

/**
 * Read-only replay surface (05B §7.3, 09 §10.4).
 *
 * Loads the replay manifest + event history and renders the synchronized
 * player. The review page embeds it while the session is still submittable;
 * after submission the same component is reused as the read-only replay.
 *
 * Manifest and events are fetched independently: an event-history failure must
 * not blank a playable recording. `reload({ preservePosition: true })` keeps
 * the current playback position across the fresh presigned URLs.
 */

const EVENT_PAGE_SIZE = 100

const props = withDefaults(
  defineProps<{
    sessionId: string
    initialTimeMs?: number
  }>(),
  {
    initialTimeMs: 0,
  },
)

const playerRef = ref<InstanceType<typeof ReplayPlayer> | null>(null)

const replay = ref<ReplayView | null>(null)
const events = ref<ReplayEvent[]>([])
const loading = ref(true)
const error = ref('')
const eventsError = ref('')
const eventsTruncated = ref(false)
let disposed = false

async function reload(options: { preservePosition?: boolean } = {}): Promise<void> {
  if (!props.sessionId || disposed) {
    return
  }
  if (options.preservePosition) {
    playerRef.value?.beginReload()
  }
  loading.value = true
  error.value = ''
  eventsError.value = ''
  const [manifestResult, eventsResult] = await Promise.allSettled([
    getReplay(props.sessionId),
    getEvents(props.sessionId, 0, EVENT_PAGE_SIZE),
  ])
  if (disposed) {
    return
  }
  loading.value = false

  if (manifestResult.status === 'rejected') {
    error.value = messageOf(manifestResult.reason, '回放加载失败，请重试。')
    return
  }
  replay.value = manifestResult.value

  if (eventsResult.status === 'fulfilled') {
    events.value = eventsResult.value.items
    eventsTruncated.value = eventsResult.value.items.length >= EVENT_PAGE_SIZE
  } else {
    eventsError.value = messageOf(eventsResult.reason, '事件加载失败。')
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
      <ElButton size="small" data-testid="replay-retry" @click="reload()">重试</ElButton>
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

      <ElAlert
        v-if="eventsError"
        class="replay-page__state"
        type="warning"
        :closable="false"
        show-icon
        data-testid="replay-events-error"
      >
        <template #title>{{ eventsError }}</template>
      </ElAlert>

      <p v-if="eventsTruncated" class="replay-page__hint" data-testid="events-truncated">
        事件较多，仅显示前 {{ EVENT_PAGE_SIZE }} 条。
      </p>

      <ReplayPlayer
        ref="playerRef"
        :replay="replay"
        :events="events"
        :initial-time-ms="initialTimeMs"
        @refresh="reload()"
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

.replay-page__hint {
  margin: 0;
  color: #94a3b8;
  font-size: 12px;
}
</style>