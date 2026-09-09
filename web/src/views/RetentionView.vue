<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import api from '@/services/api'
import GameSelector from '@/components/GameSelector.vue'
import TrendChart from '@/components/TrendChart.vue'
import { useGameList } from '@/composables/useGameList'

const { currentGameId } = useGameList()

const granularity = ref('day')
const days = ref(90)
const data = ref(null)
const loading = ref(false)
const error = ref('')

const granularityOptions = [
  { value: 'day', label: '按天' },
  { value: 'week', label: '按周' },
  { value: 'month', label: '按月' }
]
const dayOptions = [30, 90, 180, 365]

const points = computed(() => data.value?.points || [])
const summary = computed(() => data.value?.summary || {})

const chartLabels = computed(() => points.value.map((p) => String(p.cohort)))
const chartSeries = computed(() => [
  { name: '次留 (D1)', color: '#3b82f6', values: points.value.map((p) => p.d1Rate) },
  { name: '7留 (D7)', color: '#10b981', values: points.value.map((p) => p.d7Rate) },
  { name: '30留 (D30)', color: '#f59e0b', values: points.value.map((p) => p.d30Rate) }
])

async function load() {
  if (!currentGameId.value) return
  loading.value = true
  error.value = ''
  try {
    const response = await api.get(`/api/retention-metrics/${currentGameId.value}/trend`, {
      params: { granularity: granularity.value, days: days.value }
    })
    data.value = response.data
  } catch (e) {
    error.value = e.response?.data?.message || '加载留存趋势失败'
    console.error('Failed to load retention trend:', e)
  } finally {
    loading.value = false
  }
}

function pct(v) {
  return `${(v * 100).toFixed(2)}%`
}

onMounted(load)
watch([currentGameId, granularity, days], load)
</script>

<template>
  <div>
    <div class="sm:flex sm:items-center mb-8 gap-4">
      <div class="sm:flex-auto">
        <h1 class="text-2xl font-bold text-gray-900">留存趋势报表</h1>
        <p class="mt-1 text-sm text-gray-500">新增用户次留 / 7 留 / 30 留趋势（按天 / 周 / 月 cohort 对齐）</p>
      </div>
      <div class="mt-4 sm:mt-0 sm:ml-16 flex items-center gap-3 flex-wrap">
        <GameSelector />
        <select v-model="granularity" class="input !w-auto">
          <option v-for="opt in granularityOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
        </select>
        <select v-model="days" class="input !w-auto">
          <option v-for="d in dayOptions" :key="d" :value="d">近 {{ d }} 天</option>
        </select>
        <button @click="load" class="btn btn-secondary">刷新</button>
      </div>
    </div>

    <div v-if="loading" class="text-center py-12">
      <div class="animate-spin rounded-full h-12 w-12 border-b-2 border-primary-600 mx-auto"></div>
      <p class="mt-4 text-gray-500">加载中...</p>
    </div>

    <div v-else-if="error" class="card text-center py-8 text-red-600">{{ error }}</div>

    <div v-else-if="data && data.available === false" class="card text-center py-8">
      <p class="text-gray-500">ClickHouse 未配置，留存报表不可用</p>
      <p class="text-xs text-gray-400 mt-1">部署时设置 CLICKHOUSE_URL 后自动启用</p>
    </div>

    <div v-else-if="data">
      <!-- 汇总卡片 -->
      <div class="grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-4 mb-8">
        <div class="card">
          <p class="text-sm font-medium text-gray-500">窗口新增用户</p>
          <p class="text-2xl font-semibold text-gray-900 mt-1">{{ summary.totalNewUsers ?? 0 }}</p>
          <p class="text-xs text-gray-400 mt-1">{{ summary.cohorts ?? 0 }} 个 cohort</p>
        </div>
        <div class="card">
          <p class="text-sm font-medium text-gray-500">平均次留 D1</p>
          <p class="text-2xl font-semibold text-blue-600 mt-1">{{ pct(summary.avgD1Rate ?? 0) }}</p>
        </div>
        <div class="card">
          <p class="text-sm font-medium text-gray-500">平均 7 留 D7</p>
          <p class="text-2xl font-semibold text-emerald-600 mt-1">{{ pct(summary.avgD7Rate ?? 0) }}</p>
        </div>
        <div class="card">
          <p class="text-sm font-medium text-gray-500">平均 30 留 D30</p>
          <p class="text-2xl font-semibold text-amber-600 mt-1">{{ pct(summary.avgD30Rate ?? 0) }}</p>
          <p class="text-xs text-gray-400 mt-1">仅统计 {{ summary.matureCohortsD30 ?? 0 }} 个成熟 cohort</p>
        </div>
      </div>

      <!-- 趋势折线 -->
      <div class="card mb-8">
        <h3 class="text-lg font-medium text-gray-900 mb-4">留存率趋势</h3>
        <TrendChart :labels="chartLabels" :series="chartSeries" :percent="true" />
      </div>

      <!-- 明细表 -->
      <div class="card overflow-x-auto">
        <h3 class="text-lg font-medium text-gray-900 mb-4">Cohort 明细</h3>
        <table class="table" v-if="points.length > 0">
          <thead>
            <tr>
              <th>Cohort</th>
              <th>新增用户</th>
              <th>D1</th>
              <th>D1 留存率</th>
              <th>D7</th>
              <th>D7 留存率</th>
              <th>D30</th>
              <th>D30 留存率</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="p in points" :key="p.cohort">
              <td class="font-medium">{{ p.cohort }}</td>
              <td>{{ p.newUsers }}</td>
              <td>{{ p.d1 }}</td>
              <td>{{ pct(p.d1Rate) }}</td>
              <td>{{ p.d7 }}</td>
              <td>{{ pct(p.d7Rate) }}</td>
              <td>{{ p.d30 }}</td>
              <td>{{ pct(p.d30Rate) }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else class="text-center py-6 text-gray-500">窗口期内无留存数据</p>
      </div>
    </div>
  </div>
</template>
