<script setup lang="ts">
import { computed } from 'vue'

/**
 * Single-series radar for the M4 report. One series => one hue, no legend;
 * axis labels and values use text tokens, never the series color.
 *
 * Missing dimensions plot at 0 but are labelled "未提供" so the gap is never
 * mistaken for a score of zero.
 */

const props = withDefaults(
  defineProps<{
    axes: string[]
    values: (number | null)[]
  }>(),
  {},
)

const VIEW_WIDTH = 340
const VIEW_HEIGHT = 320
const CENTER_X = 170
const CENTER_Y = 160
const RADIUS = 98
const LABEL_RADIUS = 122
const RINGS = [0.25, 0.5, 0.75, 1]

interface Point {
  x: number
  y: number
  labelX: number
  labelY: number
  anchor: 'start' | 'middle' | 'end'
  baseline: 'auto' | 'middle' | 'hanging'
  value: number | null
  axis: string
}

function coords(index: number, count: number, ratio: number, radius = RADIUS) {
  const angle = -Math.PI / 2 + (index * 2 * Math.PI) / count
  return {
    x: CENTER_X + Math.cos(angle) * radius * ratio,
    y: CENTER_Y + Math.sin(angle) * radius * ratio,
    angle,
  }
}

const count = computed(() => Math.min(props.axes.length, props.values.length))

const points = computed<Point[]>(() =>
  Array.from({ length: count.value }, (_, index) => {
    const raw = props.values[index]
    const value = typeof raw === 'number' && Number.isFinite(raw) ? raw : null
    const ratio = value === null ? 0 : Math.min(100, Math.max(0, value)) / 100
    const point = coords(index, count.value, ratio)
    const label = coords(index, count.value, 1, LABEL_RADIUS)
    const cos = Math.cos(label.angle)
    const sin = Math.sin(label.angle)
    return {
      x: point.x,
      y: point.y,
      labelX: label.x,
      labelY: label.y,
      anchor: cos > 0.25 ? 'start' : cos < -0.25 ? 'end' : 'middle',
      baseline: sin < -0.5 ? 'auto' : sin > 0.5 ? 'hanging' : 'middle',
      value,
      axis: props.axes[index],
    }
  }),
)

const hasData = computed(() => points.value.some((point) => point.value !== null))

const gridPolygons = computed(() =>
  RINGS.map((ring) =>
    Array.from({ length: count.value }, (_, index) => {
      const { x, y } = coords(index, count.value, ring)
      return `${x.toFixed(1)},${y.toFixed(1)}`
    }).join(' '),
  ),
)

const axisLines = computed(() =>
  Array.from({ length: count.value }, (_, index) => {
    const outer = coords(index, count.value, 1)
    return { x1: CENTER_X, y1: CENTER_Y, x2: outer.x, y2: outer.y }
  }),
)

const dataPolygon = computed(() =>
  points.value.map((point) => `${point.x.toFixed(1)},${point.y.toFixed(1)}`).join(' '),
)

const summary = computed(() =>
  points.value
    .map((point) => `${point.axis} ${point.value === null ? '未提供' : point.value.toFixed(1)}`)
    .join('，'),
)
</script>

<template>
  <figure class="radar">
    <svg
      v-if="hasData && count >= 3"
      class="radar__svg"
      :viewBox="`0 0 ${VIEW_WIDTH} ${VIEW_HEIGHT}`"
      role="img"
      :aria-label="`维度雷达图：${summary}`"
    >
      <polygon
        v-for="(ring, index) in gridPolygons"
        :key="`ring-${index}`"
        :points="ring"
        fill="none"
        stroke="#2a3648"
        stroke-width="1"
      />
      <line
        v-for="(line, index) in axisLines"
        :key="`axis-${index}`"
        :x1="line.x1"
        :y1="line.y1"
        :x2="line.x2"
        :y2="line.y2"
        stroke="#2a3648"
        stroke-width="1"
      />

      <polygon
        :points="dataPolygon"
        fill="rgba(57, 135, 229, 0.22)"
        stroke="#3987e5"
        stroke-width="2"
        stroke-linejoin="round"
      >
        <title>各维度得分</title>
      </polygon>

      <g v-for="(point, index) in points" :key="`point-${index}`">
        <circle
          :cx="point.x"
          :cy="point.y"
          r="4"
          fill="#3987e5"
          stroke="#0b1220"
          stroke-width="2"
        >
          <title>{{ point.axis }}：{{ point.value === null ? '未提供' : `${point.value.toFixed(1)} 分` }}</title>
        </circle>
        <text
          :x="point.x"
          :y="point.y - 10"
          text-anchor="middle"
          class="radar__value"
        >
          {{ point.value === null ? '—' : point.value.toFixed(1) }}
        </text>
        <text
          :x="point.labelX"
          :y="point.labelY"
          :text-anchor="point.anchor"
          :dominant-baseline="point.baseline"
          class="radar__label"
        >
          {{ point.axis }}
          <tspan v-if="point.value === null" class="radar__missing">未提供</tspan>
        </text>
      </g>
    </svg>

    <p v-else class="radar__empty">暂无可绘制的维度得分</p>
    <figcaption class="radar__caption">满分 100 分；灰色顶点表示该维度未产出结果。</figcaption>
  </figure>
</template>

<style scoped>
.radar {
  margin: 0;
}

.radar__svg {
  display: block;
  width: 100%;
  max-width: 360px;
  margin: 0 auto;
}

.radar__label {
  fill: #94a3b8;
  font-size: 12px;
}

.radar__missing {
  fill: #64748b;
  font-size: 10px;
}

.radar__value {
  fill: #cbd5e1;
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}

.radar__empty {
  margin: 0;
  padding: 48px 0;
  color: #94a3b8;
  text-align: center;
}

.radar__caption {
  margin-top: 4px;
  color: #64748b;
  font-size: 12px;
  text-align: center;
}
</style>
