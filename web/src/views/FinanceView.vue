<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import api from '@/services/api'
import GameSelector from '@/components/GameSelector.vue'
import { useGameList } from '@/composables/useGameList'

const { currentGameId } = useGameList()

const granularity = ref('day')
const days = ref(90)
const data = ref(null)
const loading = ref(false)
const error = ref('')
const exporting = ref(false)

const granularityOptions = [
  { value: 'day', label: '按日' },
  { value: 'month', label: '按月' }
]
const dayOptions = [30, 90, 180, 365]

const rows = computed(() => data.value?.rows || [])
const summary = computed(() => data.value?.summary || {})

async function load() {
  if (!currentGameId.value) return
  loading.value = true
  error.value = ''
  try {
    const response = await api.get(`/api/finance-metrics/${currentGameId.value}/report`, {
      params: { granularity: granularity.value, days: days.value }
    })
    data.value = response.data
  } catch (e) {
    error.value = e.response?.data?.message || '加载财务指标失败'
    console.error('Failed to load finance metrics:', e)
  } finally {
    loading.value = false
  }
}

async function exportCsv() {
  if (!currentGameId.value) return
  exporting.value = true
  try {
    const response = await api.get(`/api/finance-metrics/${currentGameId.value}/export`, {
      params: { granularity: granularity.value, days: days.value },
      responseType: 'blob'
    })
    const url = window.URL.createObjectURL(new Blob([response.data], { type: 'text/csv;charset=utf-8' }))
    const link = document.createElement('a')
    link.href = url
    link.download = `finance_${currentGameId.value}_${granularity.value}.csv`
    link.click()
    window.URL.revokeObjectURL(url)
  } catch (e) {
    error.value = '导出失败，请稍后重试'
    console.error('Failed to export finance csv:', e)
  } finally {
    exporting.value = false
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
        <h1 class="text-2xl font-bold text-gray-900">财务报表</h1>
        <p class="mt-1 text-sm text-gray-500">核心指标：新增 / DAU / 收入 / 付费 / 订单 / ARPU / 付费率，支持 CSV 导出</p>
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
        <button @click="exportCsv" class="btn btn-primary" :disabled="exporting || (data && data.available === false)">
          {{ exporting ? '导出中...' : '导出 CSV' }}
        </button>
      </div>
    </div>

    <div v-if="loading" class="text-center py-12">
      <div class="animate-spin rounded-full h-12 w-12 border-b-2 border-primary-600 mx-auto"></div>
      <p class="mt-4 text-gray-500">加载中...</p>
    </div>

    <div v-else-if="error" class="card text-center py-8 text-red-600">{{ error }}</div>

    <div v-else-if="data && data.available === false" class="card text-center py-8">
      <p class="text-gray-500">ClickHouse 未配置，财务报表不可用</p>
      <p class="text-xs text-gray-400 mt-1">部署时设置 CLICKHOUSE_URL 后自动启用</p>
    </div>

    <div v-else-if="data">
      <!-- 汇总卡片 -->
      <div class="grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-5 mb-8">
        <div class="card">
          <p class="text-sm font-medium text-gray-500">窗口收入</p>
          <p class="text-2xl font-semibold text-gray-900 mt-1">{{ (summary.totalRevenue ?? 0).toLocaleString() }}</p>
        </div>
        <div class="card">
          <p class="text-sm font-medium text-gray-500">ARPU</p>
          <p class="text-2xl font-semibold text-blue-600 mt-1">{{ summary.arpu ?? 0 }}</p>
        </div>
        <div class="card">
          <p class="text-sm font-medium text-gray-500">ARPPU</p>
          <p class="text-2xl font-semibold text-indigo-600 mt-1">{{ summary.arppu ?? 0 }}</p>
        </div>
        <div class="card">
          <p class="text-sm font-medium text-gray-500">付费率</p>
          <p class="text-2xl font-semibold text-emerald-600 mt-1">{{ pct(summary.paymentRate ?? 0) }}</p>
        </div>
        <div class="card">
          <p class="text-sm font-medium text-gray-500">平均 DAU</p>
          <p class="text-2xl font-semibold text-gray-900 mt-1">{{ Math.round(summary.avgDau ?? 0).toLocaleString() }}</p>
          <p class="text-xs text-gray-400 mt-1">累计新增 {{ (summary.totalNewUsers ?? 0).toLocaleString() }}</p>
        </div>
      </div>

      <!-- 指标明细表 -->
      <div class="card overflow-x-auto">
        <h3 class="text-lg font-medium text-gray-900 mb-4">指标明细（{{ granularity === 'month' ? '按月' : '按日' }}）</h3>
        <table class="table" v-if="rows.length > 0">
          <thead>
            <tr>
              <th>统计周期</th>
              <th>新增用户</th>
              <th>DAU</th>
              <th>收入</th>
              <th>付费用户</th>
              <th>订单数</th>
              <th>ARPU</th>
              <th>ARPPU</th>
              <th>付费率</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in rows" :key="r.statDate">
              <td class="font-medium">{{ r.statDate }}</td>
              <td>{{ r.newUsers }}</td>
              <td>{{ r.dau }}</td>
              <td>{{ r.revenue }}</td>
              <td>{{ r.payers }}</td>
              <td>{{ r.orders }}</td>
              <td>{{ r.arpu }}</td>
              <td>{{ r.arppu }}</td>
              <td>{{ pct(r.paymentRate) }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else class="text-center py-6 text-gray-500">窗口期内无财务数据</p>
      </div>
    </div>
  </div>
</template>
