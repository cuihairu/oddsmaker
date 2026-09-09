<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import api from '@/services/api'
import GameSelector from '@/components/GameSelector.vue'
import TrendChart from '@/components/TrendChart.vue'
import { useGameList } from '@/composables/useGameList'

const { currentGameId } = useGameList()

const minutes = ref(5)
const autoRefresh = ref(true)
const data = ref(null)
const loading = ref(false)
const error = ref('')
let timer = null

const minuteOptions = [1, 5, 10, 30, 60]

const breakdownGroups = computed(() => [
  { title: '按平台', items: data.value?.byPlatform || [] },
  { title: '按版本', items: data.value?.byAppVersion || [] },
  { title: '按渠道', items: data.value?.byChannel || [] }
])

const trendLabels = computed(() => (data.value?.trend || []).map((p) => String(p.ts).slice(11, 16)))
const trendSeries = computed(() => [
  { name: '在线人数', color: '#3b82f6', values: (data.value?.trend || []).map((p) => p.online) }
])

async function load() {
  if (!currentGameId.value) return
  loading.value = true
  error.value = ''
  try {
    const response = await api.get(`/api/online-metrics/${currentGameId.value}`, {
      params: { minutes: minutes.value }
    })
    data.value = response.data
  } catch (e) {
    error.value = e.response?.data?.message || '加载在线监控失败'
    console.error('Failed to load online metrics:', e)
  } finally {
    loading.value = false
  }
}

function maxOf(group) {
  return Math.max(1, ...group.items.map((i) => i.online))
}

function setupTimer() {
  clearInterval(timer)
  if (autoRefresh.value) {
    timer = setInterval(load, 30_000)
  }
}

onMounted(() => {
  load()
  setupTimer()
})
onUnmounted(() => clearInterval(timer))
watch([currentGameId, minutes], load)
watch(autoRefresh, setupTimer)
</script>

<template>
  <div>
    <div class="sm:flex sm:items-center mb-8 gap-4">
      <div class="sm:flex-auto">
        <h1 class="text-2xl font-bold text-gray-900">实时在线监控</h1>
        <p class="mt-1 text-sm text-gray-500">近 N 分钟有事件上报的独立玩家数，按平台 / 版本 / 渠道聚合</p>
      </div>
      <div class="mt-4 sm:mt-0 sm:ml-16 flex items-center gap-3 flex-wrap">
        <GameSelector />
        <select v-model="minutes" class="input !w-auto">
          <option v-for="m in minuteOptions" :key="m" :value="m">近 {{ m }} 分钟</option>
        </select>
        <label class="flex items-center gap-2 text-sm text-gray-600">
          <input type="checkbox" v-model="autoRefresh" class="rounded border-gray-300 text-primary-600 focus:ring-primary-500" />
          30s 自动刷新
        </label>
        <button @click="load" class="btn btn-secondary" :disabled="loading">刷新</button>
      </div>
    </div>

    <div v-if="loading && !data" class="text-center py-12">
      <div class="animate-spin rounded-full h-12 w-12 border-b-2 border-primary-600 mx-auto"></div>
      <p class="mt-4 text-gray-500">加载中...</p>
    </div>

    <div v-else-if="error" class="card text-center py-8 text-red-600">{{ error }}</div>

    <div v-else-if="data && data.available === false" class="card text-center py-8">
      <p class="text-gray-500">ClickHouse 未配置，在线监控不可用</p>
      <p class="text-xs text-gray-400 mt-1">部署时设置 CLICKHOUSE_URL 后自动启用</p>
    </div>

    <div v-else-if="data">
      <!-- 在线总数 -->
      <div class="card mb-8 flex items-center gap-6">
        <div class="flex-shrink-0">
          <div class="w-14 h-14 rounded-full bg-green-100 flex items-center justify-center">
            <span class="relative flex h-3 w-3">
              <span class="animate-ping absolute inline-flex h-full w-full rounded-full bg-green-400 opacity-75"></span>
              <span class="relative inline-flex rounded-full h-3 w-3 bg-green-500"></span>
            </span>
          </div>
        </div>
        <div>
          <p class="text-sm font-medium text-gray-500">当前在线（近 {{ data.minutes }} 分钟）</p>
          <p class="text-4xl font-bold text-gray-900">{{ (data.online ?? 0).toLocaleString() }}</p>
        </div>
      </div>

      <!-- 维度分组 -->
      <div class="grid grid-cols-1 gap-5 lg:grid-cols-3 mb-8">
        <div v-for="group in breakdownGroups" :key="group.title" class="card">
          <h3 class="text-base font-medium text-gray-900 mb-4">{{ group.title }}</h3>
          <div v-if="group.items.length > 0" class="space-y-3">
            <div v-for="item in group.items.slice(0, 10)" :key="item.key">
              <div class="flex items-center justify-between text-sm mb-1">
                <span class="text-gray-700 font-medium truncate">{{ item.key }}</span>
                <span class="text-gray-500">{{ item.online.toLocaleString() }}</span>
              </div>
              <div class="w-full bg-gray-100 rounded-full h-2">
                <div class="h-2 rounded-full bg-primary-500" :style="{ width: (item.online / maxOf(group)) * 100 + '%' }"></div>
              </div>
            </div>
          </div>
          <p v-else class="text-sm text-gray-400 py-4 text-center">暂无数据</p>
        </div>
      </div>

      <!-- 分钟趋势 -->
      <div class="card">
        <h3 class="text-lg font-medium text-gray-900 mb-4">在线人数分钟趋势（近 {{ data.trendMinutes }} 分钟）</h3>
        <TrendChart :labels="trendLabels" :series="trendSeries" />
      </div>
    </div>
  </div>
</template>
