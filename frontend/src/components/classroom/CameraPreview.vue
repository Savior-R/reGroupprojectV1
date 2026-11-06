<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'

/**
 * Camera thumbnail (09 §6.5). The page owns the acquired stream; this
 * component only attaches it to a muted, inline, autoplaying `<video>`.
 */

const props = withDefaults(
  defineProps<{
    stream?: MediaStream | null
    label?: string
  }>(),
  {
    stream: null,
    label: '我的画面',
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
  <div class="camera-preview">
    <video
      ref="video"
      class="camera-preview__video"
      muted
      autoplay
      playsinline
      data-testid="camera-video"
    />
    <span class="camera-preview__label">{{ stream ? label : '摄像头未就绪' }}</span>
  </div>
</template>

<style scoped>
.camera-preview {
  position: absolute;
  right: 16px;
  bottom: 16px;
  width: 220px;
  aspect-ratio: 4 / 3;
  overflow: hidden;
  border: 1px solid rgba(148, 163, 184, 0.35);
  border-radius: 12px;
  background: #020617;
  box-shadow: 0 12px 30px rgba(0, 0, 0, 0.45);
}

.camera-preview__video {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.camera-preview__label {
  position: absolute;
  left: 8px;
  bottom: 6px;
  padding: 1px 8px;
  border-radius: 999px;
  background: rgba(2, 6, 23, 0.65);
  color: #cbd5e1;
  font-size: 11px;
}
</style>