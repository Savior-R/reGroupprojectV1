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
const MAX_EVENT_PAGES = 20
const MAX_EVENT_ROWS = 2_000

/**
 * Control-plane events that carry no replay value. `session.time_tick` rows
 * dominate persisted history (one or more per second), so filtering them is
 * required before applying the display cap.
 */
const NON_REPLAYABLE_EVENT_TYPES = new Set([
  'session.time_tick',
  'session.resumed',
  'ping',
  'pong',
  'time.sync',
])

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

/**
 * Fetches the event history with `after_seq` pagination, dropping
 * non-replayable control rows before applying the display cap. The loop stops
 * once the feed ends, when more than `EVENT_PAGE_SIZE` meaningful events have
 * been seen (so the truncation hint is accurate), or at the page/row bound.
 */
async function loadReplayEvents(
  sessionId: string,
): Promise<{ items: ReplayEvent[]; truncated: boolean }> {
  const meaningful: ReplayEvent[] = []
  let afterSeq = 0
  let rowsFetched = 0
  let pages = 0
  let feedEnded = false

  while (
    meaningful.length <= EVENT_PAGE_SIZE &&
    !feedEnded &&
    pages < MAX_EVENT_PAGES &&
    rowsFetched < MAX_EVENT_ROWS
  ) {
    const limit = Math.min(EVENT_PAGE_SIZE, MAX_EVENT_ROWS - rowsFetched)
    const page = await getEvents(sessionId, afterSeq, limit)
    pages += 1
    rowsFetched += page.items.length

    let nextAfterSeq = afterSeq
    for (const item of page.items) {
      if (item.seq > nextAfterSeq) {
        nextAfterSeq = item.seq
      }
      if (!NON_REPLAYABLE_EVENT_TYPES.has(item.type)) {
        meaningful.push(item)
      }
    }

    // A short page is the last page. A page that does not advance the cursor
    // would loop forever, so treat it as the end as well.
    if (page.items.length < limit || nextAfterSeq <= afterSeq) {
      feedEnded = true
      break
    }
    afterSeq = nextAfterSeq
  }

  return {
    items: meaningful.slice(0, EVENT_PAGE_SIZE),
    // More meaningful events are already in hand, or the bounded scan stopped
    // while the feed still has rows and at least one full page is shown.
    truncated:
      meaningful.length > EVENT_PAGE_SIZE ||
      (!feedEnded && meaningful.length >= EVENT_PAGE_SIZE),
  }
}

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
    loadReplayEvents(props.sessionId),
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
    eventsTruncated.value = eventsResult.value.truncated
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