<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'

/**
 * Shared-screen main stage (09 §6.5). The page owns the acquired stream; this
 * component only attaches it to a muted, inline, autoplaying `<video>`.
 */

const props = withDefaults(
  defineProps<{
    stream?: MediaStream | null
    label?: string
  }>(),
  {
    stream: null,
    label: '共享屏幕',
  },
)

const video = ref<HTMLVideoElement | null>(null)

function attach(): void {
  const element = video.value
  if (!element) {
    return
  }
  try {
    if (element.srcObject !== (props.stream ?? null)) {
      element.srcObject = props.stream ?? null
    }
  } catch {
    // jsdom and some embedded browsers do not implement `srcObject`.
  }
  if (props.stream) {
    try {
      const playing = element.play?.()
      if (playing && typeof playing.catch === 'function') {
        playing.catch(() => undefined)
      }
    } catch {
      // Autoplay policies may reject a programmatic play; the attribute retries.
    }
  }
}

watch(() => props.stream, attach, { immediate: true, flush: 'post' })
onMounted(attach)
</script>

<template>
  <div class="screen-share">
    <video
      ref="video"
      class="screen-share__video"
      muted
      autoplay
      playsinline
      data-testid="screen-video"
    />
    <span v-if="!stream" class="screen-share__placeholder">等待屏幕共享…</span>
    <span v-else class="screen-share__label">{{ label }}</span>
  </div>
</template>

<style scoped>
.screen-share {
  position: relative;
  display: flex;
  width: 100%;
  height: 100%;
  min-height: 0;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border: 1px solid rgba(148, 163, 184, 0.2);
  border-radius: 12px;
  background: #020617;
}

.screen-share__video {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.screen-share__placeholder {
  position: absolute;
  color: #94a3b8;
  font-size: 14px;
}

.screen-share__label {
  position: absolute;
  left: 12px;
  top: 10px;
  padding: 2px 10px;
  border-radius: 999px;
  background: rgba(2, 6, 23, 0.65);
  color: #cbd5e1;
  font-size: 12px;
}
</style>