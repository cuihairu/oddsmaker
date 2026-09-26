<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import api from '@/services/api'
import GameSelector from '@/components/GameSelector.vue'
import { useGameList } from '@/composables/useGameList'

const { currentGameId } = useGameList()

const environment = ref('')
const autoRefresh = ref(true)
const rows = ref([])
const loading = ref(false)
const error = ref('')
let timer = null

const environmentOptions = [
  { value: '', label: '全部环境' },
  { value: 'dev', label: 'dev' },
  { value: 'staging', label: 'staging' },
  { value: 'prod', label: 'prod' }
]

const sourceTypeLabels = {
  mysql: 'MySQL',
  postgres: 'PostgreSQL',
  csv: 'CSV 目录',
  excel: 'Excel 目录',
  kafka: 'Kafka',
  unknown: '未知'
}

// 心跳健康度：仅由 Agent 实际上报的心跳时间推导，非 Agent 侧数据不推断
const HEARTBEAT_FRESH_SECONDS = 180     // 3 个默认上报周期内视为正常
const HEARTBEAT_STALE_SECONDS = 900     // 15 分钟未心跳视为迟滞

function health(row) {
  const s = row.sinceLastPushSeconds
  if (s == null) return { key: 'never', label: '未上报', cls: 'bg-gray-100 text-gray-500' }
  if (s <= HEARTBEAT_FRESH_SECONDS) return { key: 'ok', label: '心跳正常', cls: 'bg-green-100 text-green-700' }
  if (s <= HEARTBEAT_STALE_SECONDS) return { key: 'stale', label: '心跳迟滞', cls: 'bg-amber-100 text-amber-700' }
  return { key: 'lost', label: '疑似失联', cls: 'bg-red-100 text-red-700' }
}

function fmtLag(seconds) {
  if (seconds == null) return '—'
  if (seconds < 60) return `${seconds} 秒`
  if (seconds < 3600) return `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`
  return `${Math.floor(seconds / 3600)} 小时 ${Math.floor((seconds % 3600) / 60)} 分`
}

function fmtTime(ts) {
  if (!ts) return '—'
  return String(ts).replace('T', ' ').slice(0, 19)
}

const sortedRows = computed(() =>
  [...rows.value].sort((a, b) => (a.sinceLastPushSeconds ?? Infinity) - (b.sinceLastPushSeconds ?? Infinity)))

async function load() {
  if (!currentGameId.value) return
  loading.value = true
  error.value = ''
  try {
    const params = {}
    if (environment.value) params.environment = environment.value
    const response = await api.get(`/api/dimensions/sync-status`, { params: { gameId: currentGameId.value, ...params } })
    rows.value = response.data || []
  } catch (e) {
    if (e.response?.status === 403) {
      error.value = '没有 dimension:read 权限，无法查看维度同步状态'
    } else {
      error.value = e.response?.data?.message || '加载维度同步状态失败'
    }
    console.error('Failed to load dimension sync status:', e)
  } finally {
    loading.value = false
  }
}

function setupTimer() {
  clearInterval(timer)
  if (autoRefresh.value) {
    timer = setInterval(load, 5_000)
  }
}

onMounted(() => {
  load()
  setupTimer()
})
onUnmounted(() => clearInterval(timer))
watch([currentGameId, environment], load)
watch(autoRefresh, setupTimer)
</script>

<template>
  <div>
    <div class="sm:flex sm:items-center mb-8 gap-4">
      <div class="sm:flex-auto">
        <h1 class="text-2xl font-bold text-gray-900">维度同步</h1>
        <p class="mt-1 text-sm text-gray-500">
          游戏方内网维度同步 Agent 的心跳、水位与延迟（两口径：距上次推送 / 距源头最新变更）。
          Agent 每次轮询上报心跳；本页为只读观测面，上报走 Agent 侧 dimension:manage 通道。
        </p>
      </div>
      <div class="mt-4 sm:mt-0 sm:ml-16 flex items-center gap-3 flex-wrap">
        <GameSelector />
        <select v-model="environment" class="input !w-auto">
          <option v-for="e in environmentOptions" :key="e.value" :value="e.value">{{ e.label }}</option>
        </select>
        <label class="flex items-center gap-2 text-sm text-gray-600">
          <input type="checkbox" v-model="autoRefresh" class="rounded border-gray-300 text-primary-600 focus:ring-primary-500" />
          5s 自动刷新
        </label>
        <button @click="load" class="btn btn-secondary" :disabled="loading">刷新</button>
      </div>
    </div>

    <div v-if="loading && rows.length === 0" class="text-center py-12">
      <div class="animate-spin rounded-full h-12 w-12 border-b-2 border-primary-600 mx-auto"></div>
      <p class="mt-4 text-gray-500">加载中...</p>
    </div>

    <div v-else-if="error" class="card text-center py-8 text-red-600">{{ error }}</div>

    <div v-else-if="!currentGameId" class="card text-center py-8">
      <p class="text-gray-500">请先选择游戏</p>
    </div>

    <div v-else-if="sortedRows.length === 0" class="card text-center py-12">
      <p class="text-gray-500">暂无同步源上报记录</p>
      <p class="text-xs text-gray-400 mt-2">
        部署 dimension-sync-agent（游戏方内网）并完成第一次心跳上报后，这里会出现对应数据源的状态行
      </p>
    </div>

    <div v-else class="card overflow-x-auto">
      <table class="min-w-full text-sm">
        <thead>
          <tr class="text-left text-xs text-gray-500 uppercase border-b border-gray-200">
            <th class="py-2 pr-4">状态</th>
            <th class="py-2 pr-4">源类型</th>
            <th class="py-2 pr-4">源标识</th>
            <th class="py-2 pr-4">环境</th>
            <th class="py-2 pr-4">水位 / 游标</th>
            <th class="py-2 pr-4">距上次推送</th>
            <th class="py-2 pr-4">距源头最新变更</th>
            <th class="py-2 pr-4">累计推送</th>
            <th class="py-2 pr-4">最近心跳</th>
            <th class="py-2 pr-4">最近错误</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in sortedRows" :key="r.id" class="border-b border-gray-100 last:border-0 align-top">
            <td class="py-2 pr-4">
              <span class="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium" :class="health(r).cls">
                {{ health(r).label }}
              </span>
            </td>
            <td class="py-2 pr-4">
              <span class="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-blue-50 text-blue-700">
                {{ sourceTypeLabels[r.sourceType] || r.sourceType || '未知' }}
              </span>
            </td>
            <td class="py-2 pr-4 font-mono text-xs">{{ r.sourceKey }}</td>
            <td class="py-2 pr-4 text-xs text-gray-600">{{ r.environment }}</td>
            <td class="py-2 pr-4 font-mono text-xs text-gray-600 max-w-[16rem] break-all">{{ r.cursor || '—' }}</td>
            <td class="py-2 pr-4" :class="r.sinceLastPushSeconds > HEARTBEAT_STALE_SECONDS ? 'text-red-600' : 'text-gray-700'">
              {{ fmtLag(r.sinceLastPushSeconds) }}
            </td>
            <td class="py-2 pr-4 text-gray-700">
              {{ fmtLag(r.sinceLastEventSeconds) }}
              <div v-if="r.sinceLastEventSeconds == null" class="text-xs text-gray-400">尚无打点</div>
            </td>
            <td class="py-2 pr-4 text-gray-700">
              {{ r.pushedCount ?? '—' }}
              <div v-if="r.errorCount" class="text-xs text-red-500">失败 {{ r.errorCount }}</div>
            </td>
            <td class="py-2 pr-4 font-mono text-xs text-gray-500">{{ fmtTime(r.lastPushAt) }}</td>
            <td class="py-2 pr-4 text-xs max-w-[20rem]">
              <div v-if="r.lastError" class="text-red-600 break-all">{{ r.lastError }}</div>
              <div v-else class="text-gray-400">—</div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
