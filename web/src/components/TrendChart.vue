<script setup>
import { computed } from 'vue'

const props = defineProps({
  // x 轴标签
  labels: { type: Array, default: () => [] },
  // 序列：[{ name, color, values: [Number|null] }]
  series: { type: Array, default: () => [] },
  // 数值是否为 0-1 比率（图例与 y 轴按百分比显示）
  percent: { type: Boolean, default: false },
  height: { type: Number, default: 280 }
})

const W = 800
const H = 300
const PAD = { top: 16, right: 16, bottom: 34, left: 56 }

const yMax = computed(() => {
  let max = 0
  for (const s of props.series) {
    for (const v of s.values || []) {
      if (v != null && v > max) max = v
    }
  }
  return max > 0 ? max * 1.1 : 1
})

const plotW = computed(() => W - PAD.left - PAD.right)
const plotH = computed(() => H - PAD.top - PAD.bottom)

const n = computed(() => Math.max(props.labels.length, 1))

function x(i) {
  return PAD.left + (n.value <= 1 ? plotW.value / 2 : (i * plotW.value) / (n.value - 1))
}

function y(v) {
  return PAD.top + (1 - v / yMax.value) * plotH.value
}

// 每条序列按 null 断点拆成多段 polyline
const polylines = computed(() =>
  props.series.map((s) => {
    const segments = []
    let current = []
    ;(s.values || []).forEach((v, i) => {
      if (v == null) {
        if (current.length > 0) segments.push(current)
        current = []
      } else {
        current.push(`${x(i)},${y(v)}`)
      }
    })
    if (current.length > 0) segments.push(current)
    return { name: s.name, color: s.color, segments }
  })
)

const gridLines = computed(() =>
  [0, 0.25, 0.5, 0.75, 1].map((r) => ({
    y: PAD.top + r * plotH.value,
    label: formatValue(yMax.value * (1 - r))
  }))
)

// x 轴稀疏标签：最多 8 个
const xTicks = computed(() => {
  const count = props.labels.length
  if (count === 0) return []
  const step = Math.max(1, Math.ceil(count / 8))
  const ticks = []
  for (let i = 0; i < count; i += step) {
    ticks.push({ x: x(i), label: props.labels[i] })
  }
  return ticks
})

function formatValue(v) {
  if (props.percent) {
    return `${(v * 100).toFixed(1)}%`
  }
  if (v >= 10000) {
    return `${Math.round(v / 1000)}k`
  }
  return Number.isInteger(v) ? String(v) : v.toFixed(2)
}
</script>

<template>
  <div>
    <div class="flex flex-wrap items-center gap-4 mb-2">
      <div v-for="line in polylines" :key="line.name" class="flex items-center gap-1.5">
        <span class="inline-block w-3 h-0.5 rounded" :style="{ backgroundColor: line.color }"></span>
        <span class="text-xs text-gray-600">{{ line.name }}</span>
      </div>
    </div>
    <svg
      :viewBox="`0 0 ${W} ${H}`"
      :style="{ height: height + 'px' }"
      class="w-full"
      preserveAspectRatio="none"
      role="img"
    >
      <!-- 网格与 y 轴刻度 -->
      <g v-for="(g, i) in gridLines" :key="i">
        <line :x1="PAD.left" :x2="W - PAD.right" :y1="g.y" :y2="g.y" stroke="#e5e7eb" stroke-width="1" />
        <text :x="PAD.left - 8" :y="g.y + 4" text-anchor="end" class="fill-gray-400" font-size="11">
          {{ g.label }}
        </text>
      </g>
      <!-- x 轴标签 -->
      <g v-for="(t, i) in xTicks" :key="'x' + i">
        <text :x="t.x" :y="H - 10" text-anchor="middle" class="fill-gray-400" font-size="11">
          {{ t.label }}
        </text>
      </g>
      <!-- 折线 -->
      <g v-for="line in polylines" :key="line.name">
        <polyline
          v-for="(seg, i) in line.segments"
          :key="i"
          :points="seg.join(' ')"
          fill="none"
          :stroke="line.color"
          stroke-width="2"
          stroke-linejoin="round"
          stroke-linecap="round"
        />
      </g>
      <text v-if="labels.length === 0" :x="W / 2" :y="H / 2" text-anchor="middle" class="fill-gray-300" font-size="14">
        暂无数据
      </text>
    </svg>
  </div>
</template>
