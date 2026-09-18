<script setup>
import { ref, watch, onMounted } from 'vue'
import api from '@/services/api'
import GameSelector from '@/components/GameSelector.vue'
import { useGameList } from '@/composables/useGameList'

const { currentGameId } = useGameList()

const configs = ref([])
const stats = ref(null)
const loading = ref(false)
const error = ref('')
const testingId = ref(null)
const testResult = ref(null)   // { configName, ...投递结果 }
const logTarget = ref(null)    // 日志弹层对应的配置
const logs = ref([])
const logsLoading = ref(false)

const statusColors = {
  ACTIVE: 'bg-green-100 text-green-700',
  INACTIVE: 'bg-gray-100 text-gray-500',
  PAUSED: 'bg-yellow-100 text-yellow-700',
  FAILED: 'bg-red-100 text-red-700'
}
const deliveryColors = {
  SUCCESS: 'bg-green-100 text-green-700',
  FAILED: 'bg-red-100 text-red-700',
  SENDING: 'bg-blue-100 text-blue-700',
  RETRYING: 'bg-yellow-100 text-yellow-700',
  PENDING: 'bg-gray-100 text-gray-500'
}

async function load() {
  if (!currentGameId.value) return
  loading.value = true
  error.value = ''
  testResult.value = null
  try {
    const [cfgRes, statRes] = await Promise.all([
      api.get(`/api/webhooks/game/${currentGameId.value}`),
      api.get(`/api/webhooks/stats/${currentGameId.value}`)
    ])
    configs.value = cfgRes.data
    stats.value = statRes.data
  } catch (e) {
    error.value = e.response?.data?.message || '加载 Webhook 配置失败'
    console.error('Failed to load webhooks:', e)
  } finally {
    loading.value = false
  }
}

async function testWebhook(config) {
  testingId.value = config.id
  testResult.value = null
  error.value = ''
  try {
    const res = await api.post(`/api/webhooks/test/${config.id}`, null, {
      params: { gameId: currentGameId.value }
    })
    testResult.value = { configName: config.displayName || config.name, ...res.data }
  } catch (e) {
    error.value = e.response?.data?.message || '测试发送失败'
  } finally {
    testingId.value = null
  }
}

async function openLogs(config) {
  logTarget.value = config
  logs.value = []
  logsLoading.value = true
  try {
    const res = await api.get(`/api/webhooks/logs/${config.id}`, {
      params: { gameId: currentGameId.value }
    })
    logs.value = res.data
  } catch (e) {
    error.value = e.response?.data?.message || '加载发送日志失败'
  } finally {
    logsLoading.value = false
  }
}

function fmtTime(ts) {
  return ts ? String(ts).replace('T', ' ').slice(0, 19) : '-'
}

onMounted(load)
watch(currentGameId, load)
</script>

<template>
  <div>
    <div class="sm:flex sm:items-center mb-8 gap-4">
      <div class="sm:flex-auto">
        <h1 class="text-2xl font-bold text-gray-900">Webhook 通知</h1>
        <p class="mt-1 text-sm text-gray-500">
          业务事件的外发通知：配置订阅（创建走 API / seed）、连通性测试与发送日志
        </p>
      </div>
      <div class="mt-4 sm:mt-0 sm:ml-16 flex items-center gap-3 flex-wrap">
        <GameSelector />
        <button @click="load" class="btn btn-secondary" :disabled="loading">刷新</button>
      </div>
    </div>

    <div v-if="error" class="card mb-6 text-sm text-red-600">{{ error }}</div>

    <!-- 统计 -->
    <div v-if="stats" class="grid grid-cols-2 md:grid-cols-4 gap-4 mb-8">
      <div class="card">
        <p class="text-xs text-gray-500 uppercase">配置总数</p>
        <p class="text-2xl font-bold text-gray-900 mt-1">{{ stats.totalConfigs }}</p>
      </div>
      <div class="card">
        <p class="text-xs text-gray-500 uppercase">活跃配置</p>
        <p class="text-2xl font-bold text-green-600 mt-1">{{ stats.activeConfigs }}</p>
      </div>
      <div class="card">
        <p class="text-xs text-gray-500 uppercase">累计成功</p>
        <p class="text-2xl font-bold text-gray-900 mt-1">{{ stats.totalSent }}</p>
      </div>
      <div class="card">
        <p class="text-xs text-gray-500 uppercase">累计失败</p>
        <p class="text-2xl font-bold text-red-600 mt-1">{{ stats.totalFailed }}</p>
      </div>
    </div>

    <!-- 测试结果 -->
    <div v-if="testResult" class="card mb-8 text-sm">
      <span class="font-medium text-gray-900">测试「{{ testResult.configName }}」：</span>
      <span
        class="px-2 py-0.5 rounded-full text-xs font-medium ml-2"
        :class="testResult.status === 'success' ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'"
      >
        {{ testResult.status === 'success' ? '成功' : '失败' }}
      </span>
      <span class="text-gray-600 ml-2">
        <template v-if="testResult.status === 'success'">
          HTTP {{ testResult.httpStatus }}，耗时 {{ testResult.responseTimeMs }}ms
        </template>
        <template v-else>
          {{ testResult.errorType }}: {{ testResult.error }}
        </template>
        <span v-if="testResult.logId" class="text-gray-400">（日志 {{ testResult.logId }}）</span>
      </span>
    </div>

    <!-- 配置列表 -->
    <div class="card overflow-x-auto">
      <h3 class="text-base font-medium text-gray-900 mb-4">Webhook 配置（{{ configs.length }}）</h3>
      <table v-if="configs.length" class="min-w-full divide-y divide-gray-200 text-sm">
        <thead>
          <tr class="text-left text-xs text-gray-500 uppercase">
            <th class="py-2 pr-4">名称</th>
            <th class="py-2 pr-4">URL</th>
            <th class="py-2 pr-4">状态</th>
            <th class="py-2 pr-4">订阅事件</th>
            <th class="py-2 pr-4">环境</th>
            <th class="py-2 pr-4">成功 / 失败</th>
            <th class="py-2 pr-4">最近发送</th>
            <th class="py-2">操作</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-gray-100">
          <tr v-for="config in configs" :key="config.id">
            <td class="py-3 pr-4 font-medium text-gray-900">
              {{ config.displayName || config.name }}
              <p v-if="config.description" class="text-xs text-gray-500 mt-0.5">{{ config.description }}</p>
            </td>
            <td class="py-3 pr-4 text-gray-500 text-xs max-w-[220px] truncate" :title="config.webhookUrl">
              {{ config.webhookUrl }}
            </td>
            <td class="py-3 pr-4">
              <span class="px-2 py-0.5 rounded-full text-xs font-medium" :class="statusColors[config.status]">
                {{ config.status }}
              </span>
            </td>
            <td class="py-3 pr-4 text-gray-600 text-xs">
              <span v-if="!config.eventTypes" class="text-gray-400">全部事件</span>
              <template v-else>
                <span
                  v-for="t in String(config.eventTypes).split(',')"
                  :key="t"
                  class="inline-block bg-gray-100 text-gray-600 rounded px-1.5 py-0.5 mr-1 mb-0.5"
                >{{ t.trim() }}</span>
              </template>
            </td>
            <td class="py-3 pr-4 text-gray-600 text-xs">{{ config.environmentId || '全部' }}</td>
            <td class="py-3 pr-4 text-gray-600 whitespace-nowrap">
              <span class="text-green-600">{{ config.totalSuccess }}</span>
              /
              <span class="text-red-600">{{ config.totalFailed }}</span>
            </td>
            <td class="py-3 pr-4 text-gray-500 text-xs">{{ fmtTime(config.lastSentAt) }}</td>
            <td class="py-3 flex items-center gap-2 whitespace-nowrap">
              <button
                @click="testWebhook(config)"
                class="text-xs text-blue-600 hover:underline"
                :disabled="testingId === config.id"
              >
                {{ testingId === config.id ? '发送中...' : '测试' }}
              </button>
              <button @click="openLogs(config)" class="text-xs text-blue-600 hover:underline">日志</button>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else class="text-sm text-gray-400 py-6 text-center">
        {{ loading ? '加载中...' : '当前游戏暂无 Webhook 配置（可通过 API / seed 创建）' }}
      </p>
    </div>

    <!-- 发送日志弹层 -->
    <div
      v-if="logTarget"
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/40"
      @click.self="logTarget = null"
    >
      <div class="bg-white rounded-lg shadow-xl p-6 w-full max-w-3xl mx-4 max-h-[80vh] overflow-y-auto">
        <div class="flex items-center justify-between mb-4">
          <h3 class="text-base font-medium text-gray-900">
            发送日志：{{ logTarget.displayName || logTarget.name }}
          </h3>
          <button @click="logTarget = null" class="text-gray-400 hover:text-gray-600 text-sm">关闭</button>
        </div>
        <p v-if="logsLoading" class="text-sm text-gray-400 py-6 text-center">加载中...</p>
        <table v-else-if="logs.length" class="min-w-full divide-y divide-gray-200 text-sm">
          <thead>
            <tr class="text-left text-xs text-gray-500 uppercase">
              <th class="py-2 pr-4">时间</th>
              <th class="py-2 pr-4">事件</th>
              <th class="py-2 pr-4">投递状态</th>
              <th class="py-2 pr-4">HTTP</th>
              <th class="py-2 pr-4">耗时</th>
              <th class="py-2 pr-4">重试</th>
              <th class="py-2">错误</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-gray-100">
            <tr v-for="log in logs" :key="log.id">
              <td class="py-2.5 pr-4 text-gray-500 text-xs whitespace-nowrap">{{ fmtTime(log.sentAt) }}</td>
              <td class="py-2.5 pr-4 text-gray-600 text-xs">{{ log.eventType }}</td>
              <td class="py-2.5 pr-4">
                <span class="px-2 py-0.5 rounded-full text-xs font-medium" :class="deliveryColors[log.deliveryStatus]">
                  {{ log.deliveryStatus }}
                </span>
              </td>
              <td class="py-2.5 pr-4 text-gray-600">{{ log.responseStatus ?? '-' }}</td>
              <td class="py-2.5 pr-4 text-gray-600">{{ log.responseTimeMs != null ? `${log.responseTimeMs}ms` : '-' }}</td>
              <td class="py-2.5 pr-4 text-gray-600">×{{ log.retryCount ?? 0 }}</td>
              <td class="py-2.5 text-gray-500 text-xs max-w-[200px] truncate" :title="log.errorMessage">
                {{ log.errorMessage || '-' }}
              </td>
            </tr>
          </tbody>
        </table>
        <p v-else class="text-sm text-gray-400 py-6 text-center">暂无发送记录</p>
      </div>
    </div>
  </div>
</template>
