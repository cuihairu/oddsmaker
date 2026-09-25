<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import api from '@/services/api'
import GameSelector from '@/components/GameSelector.vue'
import { useGameList } from '@/composables/useGameList'

const { currentGameId } = useGameList()

const environment = ref('prod')
const outcome = ref('')
const autoRefresh = ref(true)
const data = ref(null)
const loading = ref(false)
const error = ref('')
let timer = null

const environmentOptions = ['dev', 'staging', 'prod']
const outcomeOptions = [
  { value: '', label: '全部结局' },
  { value: 'accepted', label: '已接受' },
  { value: 'rejected', label: '被拒绝' },
  { value: 'sampled_out', label: '被采样' },
  { value: 'duplicate', label: '重复吸收' }
]

const outcomeBadges = {
  accepted: 'bg-green-100 text-green-700',
  rejected: 'bg-red-100 text-red-700',
  sampled_out: 'bg-amber-100 text-amber-700',
  duplicate: 'bg-gray-100 text-gray-600'
}

const outcomeLabels = { accepted: '已接受', rejected: '被拒绝', sampled_out: '被采样', duplicate: '重复' }

const reasonLabels = {
  invalid_schema: '字段缺失或 schema 不合法',
  api_key_scope_mismatch: 'API Key 作用域与事件 game/env 不符',
  invalid_timestamp: '客户端时间戳偏差超限',
  pii_blocked: '含被拦截的 PII 字段',
  payload_too_large: '事件体超限',
  blocked: '命中封禁名单',
  kafka_error: '消息管道异常'
}

const events = computed(() => data.value?.events || [])

function formatTime(ts) {
  if (!ts) return '-'
  const d = new Date(ts)
  return d.toTimeString().slice(0, 8) + '.' + String(d.getMilliseconds()).padStart(3, '0')
}

async function load() {
  if (!currentGameId.value) return
  loading.value = true
  error.value = ''
  try {
    const params = { environment: environment.value }
    if (outcome.value) params.outcome = outcome.value
    const response = await api.get(`/api/inspector/${currentGameId.value}/recent`, { params })
    data.value = response.data
  } catch (e) {
    error.value = e.response?.data?.message || '加载实时事件检视失败'
    console.error('Failed to load inspector events:', e)
  } finally {
    loading.value = false
  }
}

function setupTimer() {
  clearInterval(timer)
  if (autoRefresh.value) {
    timer = setInterval(load, 3_000)
  }
}

onMounted(() => {
  load()
  setupTimer()
})
onUnmounted(() => clearInterval(timer))
watch([currentGameId, environment, outcome], load)
watch(autoRefresh, setupTimer)
</script>

<template>
  <div>
    <div class="sm:flex sm:items-center mb-8 gap-4">
      <div class="sm:flex-auto">
        <h1 class="text-2xl font-bold text-gray-900">实时事件检视</h1>
        <p class="mt-1 text-sm text-gray-500">
          接入调试面：每条事件的到达结局与拒绝原因（Gateway 内存保留近 10 分钟，仅元数据不含事件参数）
        </p>
      </div>
      <div class="mt-4 sm:mt-0 sm:ml-16 flex items-center gap-3 flex-wrap">
        <GameSelector />
        <select v-model="environment" class="input !w-auto">
          <option v-for="e in environmentOptions" :key="e" :value="e">{{ e }}</option>
        </select>
        <select v-model="outcome" class="input !w-auto">
          <option v-for="o in outcomeOptions" :key="o.value" :value="o.value">{{ o.label }}</option>
        </select>
        <label class="flex items-center gap-2 text-sm text-gray-600">
          <input type="checkbox" v-model="autoRefresh" class="rounded border-gray-300 text-primary-600 focus:ring-primary-500" />
          3s 自动刷新
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
      <p class="text-gray-500">{{ data.message || '实时事件检视不可用' }}</p>
      <p class="text-xs text-gray-400 mt-1">原因：{{ data.reason }}</p>
    </div>

    <div v-else-if="data">
      <div class="card mb-4 flex items-center gap-3 text-sm text-gray-500">
        <span class="relative flex h-2.5 w-2.5">
          <span class="animate-ping absolute inline-flex h-full w-full rounded-full bg-green-400 opacity-75"></span>
          <span class="relative inline-flex rounded-full h-2.5 w-2.5 bg-green-500"></span>
        </span>
        <span>{{ data.game_id }} @ {{ data.environment }} · 最近 {{ data.count }} 条</span>
      </div>

      <div v-if="events.length === 0" class="card text-center py-12">
        <p class="text-gray-500">暂无事件到达</p>
        <p class="text-xs text-gray-400 mt-2">
          用 SDK 向该游戏环境发送一条事件试试；确认 apiKey / gameId / environment 与本页一致
        </p>
      </div>

      <div v-else class="card overflow-x-auto">
        <table class="min-w-full text-sm">
          <thead>
            <tr class="text-left text-xs text-gray-500 uppercase border-b border-gray-200">
              <th class="py-2 pr-4">服务器时间</th>
              <th class="py-2 pr-4">event_id</th>
              <th class="py-2 pr-4">事件名 / 类型</th>
              <th class="py-2 pr-4">device / user</th>
              <th class="py-2 pr-4">结局</th>
              <th class="py-2 pr-4">原因</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="e in events" :key="e.event_id + e.ts_server" class="border-b border-gray-100 last:border-0">
              <td class="py-2 pr-4 font-mono text-xs text-gray-500">{{ formatTime(e.ts_server) }}</td>
              <td class="py-2 pr-4 font-mono text-xs">{{ e.event_id }}</td>
              <td class="py-2 pr-4">
                <div class="font-medium text-gray-900">{{ e.event_name || '-' }}</div>
                <div class="text-xs text-gray-400">{{ e.event_type || '' }}</div>
              </td>
              <td class="py-2 pr-4 font-mono text-xs text-gray-600">
                <div>{{ e.device_id || '-' }}</div>
                <div class="text-gray-400">{{ e.user_id || '' }}</div>
              </td>
              <td class="py-2 pr-4">
                <span class="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium"
                      :class="outcomeBadges[e.outcome] || 'bg-gray-100 text-gray-600'">
                  {{ outcomeLabels[e.outcome] || e.outcome }}
                </span>
              </td>
              <td class="py-2 pr-4 text-xs">
                <div class="text-gray-700">{{ reasonLabels[e.reason] || e.reason || '—' }}</div>
                <div v-if="e.detail" class="text-gray-400 font-mono mt-0.5">{{ e.detail }}</div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>
