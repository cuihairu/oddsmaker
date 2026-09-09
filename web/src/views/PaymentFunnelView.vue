<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import api from '@/services/api'
import GameSelector from '@/components/GameSelector.vue'
import { useGameList } from '@/composables/useGameList'

const { currentGameId } = useGameList()

const days = ref(90)
const data = ref(null)
const loading = ref(false)
const error = ref('')

const dayOptions = [30, 90, 180, 365]

const funnel = computed(() => data.value?.funnel || {})
const points = computed(() => data.value?.points || [])

// 漏斗可视化步骤：以注册数为 100% 基准
const funnelBars = computed(() => {
  const base = funnel.value.registered || 0
  return [
    { key: 'registered', label: '注册（窗口期新增）', value: funnel.value.registered ?? 0, color: '#3b82f6' },
    { key: 'firstPay', label: '首充', value: funnel.value.firstPay ?? 0, color: '#6366f1' },
    { key: 'secondPay', label: '二充', value: funnel.value.secondPay ?? 0, color: '#8b5cf6' },
    { key: 'retained30', label: '月留存（1-30 天活跃）', value: funnel.value.retained30 ?? 0, color: '#10b981' }
  ].map((step, i, arr) => ({
    ...step,
    widthPct: base > 0 ? (step.value / base) * 100 : 0,
    stepRate: i > 0 && arr[i - 1].value > 0 ? step.value / arr[i - 1].value : null
  }))
})

async function load() {
  if (!currentGameId.value) return
  loading.value = true
  error.value = ''
  try {
    const response = await api.get(`/api/payment-metrics/${currentGameId.value}/funnel`, {
      params: { days: days.value }
    })
    data.value = response.data
  } catch (e) {
    error.value = e.response?.data?.message || '加载付费漏斗失败'
    console.error('Failed to load payment funnel:', e)
  } finally {
    loading.value = false
  }
}

function pct(v) {
  return v == null ? '-' : `${(v * 100).toFixed(2)}%`
}

onMounted(load)
watch([currentGameId, days], load)
</script>

<template>
  <div>
    <div class="sm:flex sm:items-center mb-8 gap-4">
      <div class="sm:flex-auto">
        <h1 class="text-2xl font-bold text-gray-900">付费漏斗分析</h1>
        <p class="mt-1 text-sm text-gray-500">窗口期新增用户：注册 → 首充 → 二充 → 月留存 转化</p>
      </div>
      <div class="mt-4 sm:mt-0 sm:ml-16 flex items-center gap-3 flex-wrap">
        <GameSelector />
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
      <p class="text-gray-500">ClickHouse 未配置，付费漏斗不可用</p>
      <p class="text-xs text-gray-400 mt-1">部署时设置 CLICKHOUSE_URL 后自动启用</p>
    </div>

    <div v-else-if="data">
      <!-- 漏斗可视化 -->
      <div class="card mb-8">
        <h3 class="text-lg font-medium text-gray-900 mb-6">转化漏斗（以注册数为基准）</h3>
        <div class="space-y-5">
          <div v-for="step in funnelBars" :key="step.key">
            <div class="flex items-center justify-between mb-1">
              <span class="text-sm font-medium text-gray-700">{{ step.label }}</span>
              <span class="text-sm text-gray-500">
                {{ step.value.toLocaleString() }} 人
                <span v-if="step.stepRate != null" class="ml-2 text-gray-400">
                  上一步转化 {{ pct(step.stepRate) }}
                </span>
              </span>
            </div>
            <div class="w-full bg-gray-100 rounded-full h-8 overflow-hidden">
              <div
                class="h-8 rounded-full transition-all duration-500 flex items-center justify-end pr-3"
                :style="{ width: Math.max(step.widthPct, step.value > 0 ? 2 : 0) + '%', backgroundColor: step.color }"
              >
                <span class="text-xs font-medium text-white">{{ step.widthPct.toFixed(1) }}%</span>
              </div>
            </div>
          </div>
        </div>
        <p class="text-xs text-gray-400 mt-4">
          月留存以 {{ funnel.retained30Base ?? 0 }} 名成熟 cohort 注册用户为分母（30 天留存窗口已关闭）
        </p>
      </div>

      <!-- 关键指标 -->
      <div class="grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-4 mb-8">
        <div class="card">
          <p class="text-sm font-medium text-gray-500">首充率</p>
          <p class="text-2xl font-semibold text-indigo-600 mt-1">{{ pct(funnel.firstPayRate) }}</p>
        </div>
        <div class="card">
          <p class="text-sm font-medium text-gray-500">二充率（首充 → 二充）</p>
          <p class="text-2xl font-semibold text-purple-600 mt-1">{{ pct(funnel.firstToSecondRate) }}</p>
        </div>
        <div class="card">
          <p class="text-sm font-medium text-gray-500">付费用户月留存</p>
          <p class="text-2xl font-semibold text-emerald-600 mt-1">{{ pct(funnel.retained30Rate) }}</p>
        </div>
        <div class="card">
          <p class="text-sm font-medium text-gray-500">注册 → 二充</p>
          <p class="text-2xl font-semibold text-gray-900 mt-1">{{ pct(funnel.secondPayRate) }}</p>
        </div>
      </div>

      <!-- Cohort 明细 -->
      <div class="card overflow-x-auto">
        <h3 class="text-lg font-medium text-gray-900 mb-4">按注册日 Cohort 明细</h3>
        <table class="table" v-if="points.length > 0">
          <thead>
            <tr>
              <th>Cohort</th>
              <th>注册</th>
              <th>首充</th>
              <th>首充率</th>
              <th>二充</th>
              <th>二充率</th>
              <th>月留存</th>
              <th>留存率</th>
              <th>状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="p in points" :key="p.cohort">
              <td class="font-medium">{{ p.cohort }}</td>
              <td>{{ p.registered }}</td>
              <td>{{ p.firstPay }}</td>
              <td>{{ pct(p.firstPayRate) }}</td>
              <td>{{ p.secondPay }}</td>
              <td>{{ pct(p.secondPayRate) }}</td>
              <td>{{ p.retained30 }}</td>
              <td>{{ pct(p.retained30Rate) }}</td>
              <td>
                <span :class="p.mature ? 'badge badge-success' : 'badge badge-info'">
                  {{ p.mature ? '成熟' : '观察中' }}
                </span>
              </td>
            </tr>
          </tbody>
        </table>
        <p v-else class="text-center py-6 text-gray-500">窗口期内无注册用户</p>
      </div>
    </div>
  </div>
</template>
