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
const success = ref('')
const testingId = ref(null)
const testResult = ref(null)   // { configName, ...投递结果 }
const logTarget = ref(null)    // 日志弹层对应的配置
const logs = ref([])
const logsLoading = ref(false)

// 创建/编辑弹层
const showForm = ref(false)
const editing = ref(null)      // null=创建，否则为编辑目标配置
const saving = ref(false)
const form = ref(emptyForm())
const formError = ref('')

const AUTH_TYPES = [
  { value: 'none', label: '无鉴权' },
  { value: 'basic', label: 'Basic Auth' },
  { value: 'bearer', label: 'Bearer Token' },
  { value: 'api_key', label: 'API Key（自定义 header 注入）' }
]

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

function emptyForm() {
  return {
    name: '', displayName: '', webhookUrl: '', environmentId: '', description: '',
    eventTypes: '', riskLevels: '', authType: 'none', authConfig: '',
    timeoutSeconds: 30, maxRetries: 3, retryBackoffMs: 1000
  }
}

function openCreate() {
  editing.value = null
  form.value = emptyForm()
  formError.value = ''
  error.value = ''
  showForm.value = true
}

function openEdit(config) {
  editing.value = config
  form.value = {
    name: config.name || '',
    displayName: config.displayName || '',
    webhookUrl: config.webhookUrl || '',
    environmentId: config.environmentId || '',
    description: config.description || '',
    eventTypes: config.eventTypes || '',
    riskLevels: config.riskLevels || '',
    authType: config.authType || 'none',
    authConfig: '', // secret 不回显，留空 = 保留原值
    timeoutSeconds: config.timeoutSeconds ?? 30,
    maxRetries: config.maxRetries ?? 3,
    retryBackoffMs: config.retryBackoffMs ?? 1000
  }
  formError.value = ''
  error.value = ''
  showForm.value = true
}

function buildPayload() {
  const f = form.value
  const payload = {
    name: f.name.trim(),
    displayName: f.displayName.trim() || null,
    webhookUrl: f.webhookUrl.trim(),
    environmentId: f.environmentId.trim() || null,
    description: f.description.trim() || null,
    eventTypes: f.eventTypes.trim() || null,
    riskLevels: f.riskLevels.trim() || null,
    authType: f.authType,
    timeoutSeconds: Number(f.timeoutSeconds),
    maxRetries: Number(f.maxRetries),
    retryBackoffMs: Number(f.retryBackoffMs)
  }
  if (f.authConfig.trim()) payload.authConfig = f.authConfig.trim()
  return payload
}

async function submitForm() {
  const f = form.value
  if (!f.name.trim() || !f.webhookUrl.trim()) {
    formError.value = '名称与 URL 为必填项'
    return
  }
  if (f.authType !== 'none' && f.authConfig.trim()) {
    try {
      const parsed = JSON.parse(f.authConfig)
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed) || Object.keys(parsed).length === 0) {
        throw new Error('empty')
      }
    } catch {
      formError.value = 'authConfig 必须是非空 JSON 对象'
      return
    }
  } else if (f.authType !== 'none' && !editing.value) {
    formError.value = '所选鉴权类型需要填写 authConfig（JSON）'
    return
  }
  saving.value = true
  formError.value = ''
  try {
    if (editing.value) {
      await api.put(`/api/webhooks/game/${currentGameId.value}/configs/${editing.value.id}`, buildPayload())
      await load()
      success.value = `配置「${f.name.trim()}」已更新`
    } else {
      await api.post(`/api/webhooks/game/${currentGameId.value}/configs`, buildPayload())
      await load()
      success.value = `配置「${f.name.trim()}」已创建`
    }
    showForm.value = false
  } catch (e) {
    formError.value = e.response?.data?.message || '保存失败'
  } finally {
    saving.value = false
  }
}

async function toggleStatus(config) {
  const next = config.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
  error.value = ''
  success.value = ''
  try {
    await api.put(`/api/webhooks/game/${currentGameId.value}/configs/${config.id}`, { ...config, status: next })
    await load()
    success.value = `配置「${config.displayName || config.name}」已${next === 'ACTIVE' ? '启用' : '停用'}`
  } catch (e) {
    error.value = e.response?.data?.message || '状态更新失败'
  }
}

async function removeConfig(config) {
  if (!confirm(`确认删除 Webhook 配置「${config.displayName || config.name}」？删除后立即停止派发，操作不可撤销。`)) return
  error.value = ''
  success.value = ''
  try {
    await api.delete(`/api/webhooks/game/${currentGameId.value}/configs/${config.id}`)
    await load()
    success.value = `配置「${config.displayName || config.name}」已删除`
  } catch (e) {
    error.value = e.response?.data?.message || '删除失败'
  }
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
          业务事件的外发通知：配置订阅管理、连通性测试与发送日志
        </p>
      </div>
      <div class="mt-4 sm:mt-0 sm:ml-16 flex items-center gap-3 flex-wrap">
        <GameSelector />
        <button @click="openCreate" class="btn btn-primary">新建配置</button>
        <button @click="load" class="btn btn-secondary" :disabled="loading">刷新</button>
      </div>
    </div>

    <div v-if="error" class="card mb-6 text-sm text-red-600">{{ error }}</div>
    <div v-if="success" class="card mb-6 text-sm text-green-700">{{ success }}</div>

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
              <button @click="openEdit(config)" class="text-xs text-blue-600 hover:underline">编辑</button>
              <button
                @click="toggleStatus(config)"
                class="text-xs hover:underline"
                :class="config.status === 'ACTIVE' ? 'text-yellow-600' : 'text-green-600'"
              >
                {{ config.status === 'ACTIVE' ? '停用' : '启用' }}
              </button>
              <button @click="removeConfig(config)" class="text-xs text-red-600 hover:underline">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else class="text-sm text-gray-400 py-6 text-center">
        {{ loading ? '加载中...' : '当前游戏暂无 Webhook 配置，点击右上角「新建配置」创建' }}
      </p>
    </div>

    <!-- 创建/编辑弹层 -->
    <div
      v-if="showForm"
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/40"
      @click.self="showForm = false"
    >
      <div class="bg-white rounded-lg shadow-xl p-6 w-full max-w-2xl mx-4 max-h-[85vh] overflow-y-auto">
        <div class="flex items-center justify-between mb-4">
          <h3 class="text-base font-medium text-gray-900">
            {{ editing ? `编辑配置：${editing.displayName || editing.name}` : '新建 Webhook 配置' }}
          </h3>
          <button @click="showForm = false" class="text-gray-400 hover:text-gray-600 text-sm">关闭</button>
        </div>

        <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <div>
            <label class="block text-xs text-gray-500 mb-1">名称 <span class="text-red-500">*</span></label>
            <input v-model="form.name" class="input" placeholder="如 slack-alerts" />
          </div>
          <div>
            <label class="block text-xs text-gray-500 mb-1">显示名</label>
            <input v-model="form.displayName" class="input" placeholder="可选，列表优先展示" />
          </div>
          <div class="sm:col-span-2">
            <label class="block text-xs text-gray-500 mb-1">Webhook URL <span class="text-red-500">*</span></label>
            <input v-model="form.webhookUrl" class="input" placeholder="https://..." />
          </div>
          <div>
            <label class="block text-xs text-gray-500 mb-1">环境 ID</label>
            <input v-model="form.environmentId" class="input" placeholder="留空 = 全部环境" />
          </div>
          <div>
            <label class="block text-xs text-gray-500 mb-1">订阅事件</label>
            <input v-model="form.eventTypes" class="input" placeholder="逗号分隔，留空 = 全部事件" />
            <p class="text-xs text-gray-400 mt-1">
              常用：risk_case / block / review_escalation / metric_alert / quota_warning / export_completed
            </p>
          </div>
          <div>
            <label class="block text-xs text-gray-500 mb-1">风险级别过滤</label>
            <input v-model="form.riskLevels" class="input" placeholder="如 HIGH,CRITICAL，留空 = 全部" />
          </div>
          <div>
            <label class="block text-xs text-gray-500 mb-1">鉴权方式</label>
            <select v-model="form.authType" class="input">
              <option v-for="t in AUTH_TYPES" :key="t.value" :value="t.value">{{ t.label }}</option>
            </select>
          </div>
          <div v-if="form.authType !== 'none'" class="sm:col-span-2">
            <label class="block text-xs text-gray-500 mb-1">
              鉴权配置（JSON）<span v-if="!editing" class="text-red-500">*</span>
            </label>
            <textarea
              v-model="form.authConfig"
              class="input font-mono text-xs"
              rows="2"
              :placeholder="editing ? '已设置，留空保持不变' : '如 {&quot;token&quot;:&quot;...&quot;} 或 {&quot;key&quot;:&quot;X-Api-Key&quot;,&quot;value&quot;:&quot;...&quot;}'"
            ></textarea>
            <p class="text-xs text-gray-400 mt-1">服务端明文存储；保存后不再回显。api_key 需含 key（header 名）与 value 字段</p>
          </div>
          <div class="sm:col-span-2">
            <label class="block text-xs text-gray-500 mb-1">描述</label>
            <textarea v-model="form.description" class="input" rows="2" placeholder="可选"></textarea>
          </div>
          <div>
            <label class="block text-xs text-gray-500 mb-1">超时（秒，1-300）</label>
            <input v-model.number="form.timeoutSeconds" type="number" class="input" />
          </div>
          <div>
            <label class="block text-xs text-gray-500 mb-1">最大重试（0-10）</label>
            <input v-model.number="form.maxRetries" type="number" class="input" />
          </div>
          <div>
            <label class="block text-xs text-gray-500 mb-1">重试退避（毫秒，≥100）</label>
            <input v-model.number="form.retryBackoffMs" type="number" class="input" />
          </div>
        </div>

        <p v-if="formError" class="mt-4 text-sm text-red-600">{{ formError }}</p>

        <div class="mt-6 flex justify-end gap-3">
          <button @click="showForm = false" class="btn btn-secondary" :disabled="saving">取消</button>
          <button @click="submitForm" class="btn btn-primary" :disabled="saving">
            {{ saving ? '保存中...' : '保存' }}
          </button>
        </div>
      </div>
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
