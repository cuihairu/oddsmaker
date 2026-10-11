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

// ===== HTTP Pull 源配置（Control scheduler 定期拉游戏方 API，凭证加密托管） =====
const pulls = ref([])
const pullLoading = ref(false)
const pullError = ref('')
const pullSuccess = ref('')
const pullBusyId = ref(null)
const envNames = ref([])

// 创建/编辑弹层（edit 共用表单：credential 留空=保留既有凭证）
const formOpen = ref(false)
const formMode = ref('create')
const formSaving = ref(false)
const formError = ref('')
const form = ref({})

// YAML 导入/导出弹层（export 导出即取文本，import 粘贴 YAML 文本提交）
const yamlOpen = ref(false)
const yamlMode = ref('export')
const yamlText = ref('')
const yamlBusy = ref(false)
const yamlError = ref('')

const DIM_TYPES = [
  { value: 'item', label: 'item（道具/资源）' },
  { value: 'level', label: 'level（关卡）' }
]

async function loadPulls() {
  if (!currentGameId.value) return
  pullLoading.value = true
  pullError.value = ''
  try {
    const res = await api.get(`/api/games/${currentGameId.value}/dimension-pulls`)
    pulls.value = res.data || []
  } catch (e) {
    if (e.response?.status === 403) {
      pullError.value = '没有 dimension:read 权限，无法查看 HTTP Pull 配置'
    } else {
      pullError.value = e.response?.data?.message || '加载 HTTP Pull 配置失败'
    }
    console.error('Failed to load dimension pulls:', e)
  } finally {
    pullLoading.value = false
  }
}

// 环境候选：配置里的 environment 必须属于该游戏（后端校验），下拉仅为提示
async function loadEnvironments() {
  if (!currentGameId.value) return
  try {
    const res = await api.get(`/api/games/${currentGameId.value}/environments`)
    envNames.value = (res.data || []).map(e => e.name).filter(Boolean)
  } catch {
    envNames.value = []
  }
}

function openCreate() {
  formMode.value = 'create'
  form.value = {
    environment: envNames.value[0] || '',
    sourceKey: '',
    dimType: 'item',
    endpoint: '',
    credential: '',
    intervalSeconds: 300,
    pageLimit: 1000,
    enabled: true
  }
  formError.value = ''
  formOpen.value = true
}

function openEdit(pull) {
  formMode.value = 'edit'
  form.value = {
    id: pull.id,
    environment: pull.environment,
    sourceKey: pull.sourceKey,
    dimType: pull.dimType || 'item',
    endpoint: pull.endpoint,
    credential: '',   // 留空=保留既有凭证
    intervalSeconds: pull.intervalSeconds ?? 300,
    pageLimit: pull.pageLimit ?? 1000,
    enabled: pull.enabled !== false
  }
  formError.value = ''
  formOpen.value = true
}

async function submitPullForm() {
  formError.value = ''
  const environment = (form.value.environment || '').trim()
  const sourceKey = (form.value.sourceKey || '').trim()
  const endpoint = (form.value.endpoint || '').trim()
  if (!environment) return void (formError.value = '请填写环境（environment）')
  if (!sourceKey) return void (formError.value = '请填写源标识（sourceKey）')
  if (!endpoint) return void (formError.value = '请填写游戏方 API 地址（endpoint）')
  if (formMode.value === 'create' && !(form.value.credential || '').trim()) {
    return void (formError.value = '请填写 Bearer 凭证（credential）')
  }
  const payload = {
    environment,
    sourceKey,
    dimType: form.value.dimType || 'item',
    endpoint,
    intervalSeconds: Number(form.value.intervalSeconds) || 300,
    pageLimit: Number(form.value.pageLimit) || 1000,
    enabled: form.value.enabled !== false
  }
  const credential = (form.value.credential || '').trim()
  if (credential) payload.credential = credential   // 更新留空=保留既有，不提交该字段
  formSaving.value = true
  try {
    const base = `/api/games/${currentGameId.value}/dimension-pulls`
    if (formMode.value === 'create') {
      await api.post(base, payload)
    } else {
      await api.put(`${base}/${form.value.id}`, payload)
    }
    formOpen.value = false
    pullSuccess.value = formMode.value === 'create' ? `已建档「${sourceKey}」（${environment}）` : `已更新「${sourceKey}」`
    await Promise.all([loadPulls(), load()])
  } catch (e) {
    formError.value = e.response?.data?.message || '保存失败'
  } finally {
    formSaving.value = false
  }
}

async function runPull(pull) {
  pullBusyId.value = pull.id
  pullSuccess.value = ''
  try {
    const res = await api.post(`/api/games/${currentGameId.value}/dimension-pulls/${pull.id}/run`)
    const r = res.data || {}
    pullSuccess.value = r.success
      ? `「${pull.sourceKey}」本轮已推 ${r.pushed ?? 0} 条，游标 ${r.cursor || '—'}`
      : `「${pull.sourceKey}」本轮失败：${r.error || '未知错误'}`
    await Promise.all([loadPulls(), load()])
  } catch (e) {
    pullError.value = e.response?.data?.message || '手动拉取失败'
  } finally {
    pullBusyId.value = null
  }
}

async function removePull(pull) {
  if (!confirm(`确认删除 HTTP Pull 配置「${pull.sourceKey}」（${pull.environment}）？删除后该源停止拉取，且无法恢复。`)) return
  pullBusyId.value = pull.id
  pullSuccess.value = ''
  try {
    await api.delete(`/api/games/${currentGameId.value}/dimension-pulls/${pull.id}`)
    pullSuccess.value = `已删除「${pull.sourceKey}」`
    await Promise.all([loadPulls(), load()])
  } catch (e) {
    pullError.value = e.response?.data?.message || '删除失败'
  } finally {
    pullBusyId.value = null
  }
}

function openYaml(mode) {
  yamlMode.value = mode
  yamlText.value = ''
  yamlError.value = ''
  yamlOpen.value = true
  if (mode === 'export') doExport()
}

async function doExport() {
  yamlBusy.value = true
  yamlError.value = ''
  try {
    const res = await api.get(`/api/games/${currentGameId.value}/dimension-pulls/export`)
    yamlText.value = typeof res.data === 'string' ? res.data : JSON.stringify(res.data, null, 2)
  } catch (e) {
    yamlError.value = e.response?.data?.message || '导出失败'
  } finally {
    yamlBusy.value = false
  }
}

async function doImport() {
  const text = (yamlText.value || '').trim()
  if (!text) return void (yamlError.value = '请粘贴 YAML 内容')
  yamlBusy.value = true
  yamlError.value = ''
  try {
    const res = await api.post(`/api/games/${currentGameId.value}/dimension-pulls/import`, text)
    const r = res.data || {}
    yamlError.value = ''
    yamlText.value = ''
    yamlOpen.value = false
    pullSuccess.value = `已导入 ${r.total ?? 0} 条：新建 ${r.created ?? 0}、更新 ${r.updated ?? 0}`
    await Promise.all([loadPulls(), load()])
  } catch (e) {
    yamlError.value = e.response?.data?.message || '导入失败'
  } finally {
    yamlBusy.value = false
  }
}

// ===== Agent 托管配置（Control 下发源配置；密钥只回显键名，值永不下发页面） =====
const agentConfigs = ref([])
const agentLoading = ref(false)
const agentError = ref('')
const agentSuccess = ref('')

const acFormOpen = ref(false)
const acFormMode = ref('create')
const acFormSaving = ref(false)
const acFormError = ref('')
const acForm = ref({})

const AGENT_PROPS_TEMPLATE = `# 非密钥属性（每行一键；# 开头为注释）
gateway.endpoint=https://ingest.oddsmaker.local
source.type=mysql
source.jdbc.url=jdbc:mysql://replica.internal:3306/game
source.jdbc.user=oddsmaker_ro
source.jdbc.query=SELECT code AS resource_id, name, updated_at FROM items WHERE updated_at > ? ORDER BY updated_at
source.jdbc.cursor-column=updated_at`

async function loadAgentConfigs() {
  if (!currentGameId.value) {
    agentConfigs.value = []
    return
  }
  agentLoading.value = true
  agentError.value = ''
  try {
    const res = await api.get(`/api/games/${currentGameId.value}/agent-configs`)
    agentConfigs.value = res.data || []
  } catch (e) {
    agentError.value = e.response?.data?.message || 'Agent 配置加载失败'
  } finally {
    agentLoading.value = false
  }
}

function openAgentConfigCreate() {
  acFormMode.value = 'create'
  acFormError.value = ''
  acForm.value = {
    environment: envNames.value[0] || '',
    agentKey: '',
    props: AGENT_PROPS_TEMPLATE,
    secrets: '',
    enabled: true,
  }
  acFormOpen.value = true
}

function openAgentConfigEdit(cfg) {
  acFormMode.value = 'edit'
  acFormError.value = ''
  acForm.value = {
    id: cfg.id,
    environment: cfg.environment,
    agentKey: cfg.agentKey,
    props: cfg.props,
    secrets: '',
    enabled: cfg.enabled !== false,
  }
  acFormOpen.value = true
}

function parseSecrets(text) {
  // 每行 key=value；空行与 # 注释跳过
  const secrets = {}
  for (const raw of (text || '').split('\n')) {
    const line = raw.trim()
    if (!line || line.startsWith('#')) continue
    const i = line.indexOf('=')
    if (i <= 0) return { error: `secrets 每行须为 key=value（「${raw}」不是）` }
    const k = line.slice(0, i).trim()
    if (!k) return { error: 'secrets 存在空键名' }
    secrets[k] = line.slice(i + 1).trim()
  }
  return { secrets }
}

async function submitAgentConfigForm() {
  const environment = (acForm.value.environment || '').trim()
  const agentKey = (acForm.value.agentKey || '').trim()
  const props = (acForm.value.props || '').trim()
  acFormError.value = ''
  if (!environment) acFormError.value = '环境不能为空'
  else if (!agentKey) acFormError.value = 'agentKey 不能为空'
  else if (!props) acFormError.value = 'props 不能为空'
  if (acFormError.value) return
  const parsed = parseSecrets(acForm.value.secrets)
  if (parsed.error) {
    acFormError.value = parsed.error
    return
  }
  if (acFormMode.value === 'create' && !('gateway.api-key' in parsed.secrets)) {
    acFormError.value = 'secrets 至少需要 gateway.api-key'
    return
  }
  const payload = { environment, agentKey, props, enabled: acForm.value.enabled !== false }
  if (Object.keys(parsed.secrets).length) payload.secrets = parsed.secrets
  acFormSaving.value = true
  try {
    const base = `/api/games/${currentGameId.value}/agent-configs`
    if (acFormMode.value === 'create') await api.post(base, payload)
    else await api.put(`${base}/${acForm.value.id}`, payload)
    acFormOpen.value = false
    agentSuccess.value = acFormMode.value === 'create'
      ? `已建托管配置「${agentKey}」（${environment}）`
      : `已更新「${agentKey}」`
    await loadAgentConfigs()
  } catch (e) {
    acFormError.value = e.response?.data?.message || '保存失败'
  } finally {
    acFormSaving.value = false
  }
}

async function removeAgentConfig(cfg) {
  if (!confirm(`确认删除 Agent 托管配置「${cfg.agentKey}」（${cfg.environment}）？删除后该 Agent 回落本地 agent.properties。`)) return
  agentError.value = ''
  try {
    await api.delete(`/api/games/${currentGameId.value}/agent-configs/${cfg.id}`)
    agentSuccess.value = `已删除「${cfg.agentKey}」`
    await loadAgentConfigs()
  } catch (e) {
    agentError.value = e.response?.data?.message || '删除失败'
  }
}

onMounted(() => {
  load()
  loadPulls()
  loadAgentConfigs()
  loadEnvironments()
  setupTimer()
})
onUnmounted(() => clearInterval(timer))
watch([currentGameId, environment], () => {
  load()
  loadPulls()
  loadAgentConfigs()
  loadEnvironments()
})
watch(autoRefresh, setupTimer)
</script>

<template>
  <div>
    <div class="sm:flex sm:items-center mb-8 gap-4">
      <div class="sm:flex-auto">
        <h1 class="text-2xl font-bold text-gray-900">维度同步</h1>
        <p class="mt-1 text-sm text-gray-500">
          上半区：游戏方内网维度同步 Agent 的心跳、水位与延迟（只读观测面，上报走 Agent 侧 dimension:manage 通道）。
          下半区：HTTP Pull 源配置——Control scheduler 定期拉游戏方查询 API（Bearer 凭证加密托管，凭证不回显）。
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

    <!-- ===== HTTP Pull 源配置（dimension:read / dimension:manage） ===== -->
    <div class="sm:flex sm:items-center mt-10 mb-4 gap-4">
      <div class="sm:flex-auto">
        <h2 class="text-lg font-bold text-gray-900">HTTP Pull 源配置（{{ pulls.length }}）</h2>
        <p class="mt-1 text-sm text-gray-500">
          按 (环境, 源标识) 建档，Control 每 15s 巡检、按间隔拉取游戏方 API；支持 YAML 批量导入/导出
        </p>
      </div>
      <div class="mt-4 sm:mt-0 sm:ml-16 flex items-center gap-3 flex-wrap">
        <button @click="openCreate" class="btn btn-primary">新建 Pull 源</button>
        <button @click="openYaml('import')" class="btn btn-secondary">导入 YAML</button>
        <button @click="openYaml('export')" class="btn btn-secondary">导出 YAML</button>
        <button @click="loadPulls" class="btn btn-secondary" :disabled="pullLoading">刷新</button>
      </div>
    </div>

    <div v-if="pullSuccess" class="card mb-4 text-sm text-green-700">{{ pullSuccess }}</div>
    <div v-if="pullError" class="card mb-4 text-sm text-red-600">{{ pullError }}</div>

    <div v-if="!currentGameId" class="card text-center py-8">
      <p class="text-gray-500">请先选择游戏</p>
    </div>

    <div v-else class="card overflow-x-auto">
      <table v-if="pulls.length" class="min-w-full text-sm">
        <thead>
          <tr class="text-left text-xs text-gray-500 uppercase border-b border-gray-200">
            <th class="py-2 pr-4">源标识</th>
            <th class="py-2 pr-4">环境</th>
            <th class="py-2 pr-4">维度</th>
            <th class="py-2 pr-4">endpoint</th>
            <th class="py-2 pr-4">间隔 / 分页</th>
            <th class="py-2 pr-4">状态</th>
            <th class="py-2 pr-4">游标</th>
            <th class="py-2 pr-4">上次拉取</th>
            <th class="py-2 pr-4">累计推送</th>
            <th class="py-2">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="p in pulls" :key="p.id" class="border-b border-gray-100 last:border-0 align-top">
            <td class="py-2 pr-4 font-mono text-xs font-medium text-gray-900">{{ p.sourceKey }}</td>
            <td class="py-2 pr-4 text-xs text-gray-600">{{ p.environment }}</td>
            <td class="py-2 pr-4">
              <span class="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-blue-50 text-blue-700">{{ p.dimType }}</span>
            </td>
            <td class="py-2 pr-4 font-mono text-xs text-gray-600 max-w-[18rem] break-all">{{ p.endpoint }}</td>
            <td class="py-2 pr-4 text-xs text-gray-600 whitespace-nowrap">{{ p.intervalSeconds }}s / {{ p.pageLimit }}</td>
            <td class="py-2 pr-4">
              <span class="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium"
                :class="p.enabled ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-500'">
                {{ p.enabled ? '启用' : '停用' }}
              </span>
              <div v-if="p.lastError" class="text-xs text-red-500 mt-1 max-w-[14rem] break-all">失败 {{ p.errorCount }}：{{ p.lastError }}</div>
            </td>
            <td class="py-2 pr-4 font-mono text-xs text-gray-600 max-w-[12rem] break-all">{{ p.lastCursor || '—' }}</td>
            <td class="py-2 pr-4 font-mono text-xs text-gray-500">{{ fmtTime(p.lastPullAt) }}</td>
            <td class="py-2 pr-4 text-gray-700">{{ p.pushedCount ?? 0 }}</td>
            <td class="py-2 flex items-center gap-3 whitespace-nowrap">
              <button @click="runPull(p)" class="text-xs text-blue-600 hover:underline" :disabled="pullBusyId === p.id">立即拉取</button>
              <button @click="openEdit(p)" class="text-xs text-gray-600 hover:underline">编辑</button>
              <button @click="removePull(p)" class="text-xs text-red-600 hover:underline" :disabled="pullBusyId === p.id">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else class="text-sm text-gray-400 py-6 text-center">
        {{ pullLoading ? '加载中...' : '暂无 HTTP Pull 配置（点右上「新建 Pull 源」或「导入 YAML」接入）' }}
      </p>
    </div>

    <!-- ===== Agent 托管配置（dimension:read / dimension:manage；密钥只回显键名） ===== -->
    <div class="sm:flex sm:items-center mt-10 mb-4 gap-4">
      <div class="sm:flex-auto">
        <h2 class="text-lg font-bold text-gray-900">Agent 托管配置（{{ agentConfigs.length }}）</h2>
        <p class="mt-1 text-sm text-gray-500">
          下发 Agent 的源配置（props / secrets 分离托管；Agent 启动拉取一次，失败回落本地 agent.properties）
        </p>
      </div>
      <div class="mt-4 sm:mt-0 sm:ml-16 flex items-center gap-3 flex-wrap">
        <button @click="openAgentConfigCreate" class="btn btn-primary">新建托管配置</button>
        <button @click="loadAgentConfigs" class="btn btn-secondary" :disabled="agentLoading">刷新</button>
      </div>
    </div>

    <div v-if="agentSuccess" class="card mb-4 text-sm text-green-700">{{ agentSuccess }}</div>
    <div v-if="agentError" class="card mb-4 text-sm text-red-600">{{ agentError }}</div>

    <div v-if="!currentGameId" class="card text-center py-8">
      <p class="text-gray-500">请先选择游戏</p>
    </div>

    <div v-else class="card overflow-x-auto">
      <table v-if="agentConfigs.length" class="min-w-full text-sm">
        <thead>
          <tr class="text-left text-xs text-gray-500 uppercase border-b border-gray-200">
            <th class="py-2 pr-4">agentKey</th>
            <th class="py-2 pr-4">环境</th>
            <th class="py-2 pr-4">状态</th>
            <th class="py-2 pr-4">密钥键（仅名）</th>
            <th class="py-2 pr-4">更新时间</th>
            <th class="py-2">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in agentConfigs" :key="c.id" class="border-b border-gray-100 last:border-0 align-top">
            <td class="py-2 pr-4 font-mono text-xs font-medium text-gray-900">{{ c.agentKey }}</td>
            <td class="py-2 pr-4 text-xs text-gray-600">{{ c.environment }}</td>
            <td class="py-2 pr-4">
              <span class="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium"
                :class="c.enabled !== false ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-500'">
                {{ c.enabled !== false ? '启用' : '停用' }}
              </span>
            </td>
            <td class="py-2 pr-4 font-mono text-xs text-gray-600 max-w-[18rem] break-all">{{ (c.secretKeys || []).join('、') || '—' }}</td>
            <td class="py-2 pr-4 font-mono text-xs text-gray-500">{{ fmtTime(c.updatedAt) }}</td>
            <td class="py-2 flex items-center gap-3 whitespace-nowrap">
              <button @click="openAgentConfigEdit(c)" class="text-xs text-gray-600 hover:underline">编辑</button>
              <button @click="removeAgentConfig(c)" class="text-xs text-red-600 hover:underline">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else class="text-sm text-gray-400 py-6 text-center">
        {{ agentLoading ? '加载中...' : '暂无托管配置（点右上「新建托管配置」让 Agent 启动时拉取下发配置）' }}
      </p>
    </div>

    <!-- 创建/编辑 Agent 托管配置弹层（secrets 仅键名回显；编辑留空=保留既有，填写即整体轮换） -->
    <div v-if="acFormOpen" class="fixed inset-0 z-50 flex items-center justify-center bg-black/40" @click.self="acFormOpen = false">
      <div class="bg-white rounded-lg shadow-xl p-6 w-full max-w-2xl mx-4 max-h-[90vh] overflow-y-auto">
        <div class="flex items-center justify-between mb-4">
          <h3 class="text-base font-medium text-gray-900">
            {{ acFormMode === 'create' ? '新建 Agent 托管配置' : `编辑：${acForm.agentKey}（${acForm.environment}）` }}
          </h3>
          <button @click="acFormOpen = false" class="text-gray-400 hover:text-gray-600 text-sm">关闭</button>
        </div>
        <div class="grid grid-cols-2 gap-3">
          <div>
            <label class="block text-xs text-gray-500 mb-1">环境（environment，须属于该游戏）</label>
            <input v-model="acForm.environment" class="input w-full" list="pull-env-names" :disabled="acFormSaving" />
          </div>
          <div>
            <label class="block text-xs text-gray-500 mb-1">agentKey（须与 Agent status.source-key 一致）</label>
            <input v-model="acForm.agentKey" class="input w-full font-mono" :disabled="acFormSaving" />
          </div>
          <div class="col-span-2">
            <label class="block text-xs text-gray-500 mb-1">props（非密钥属性，每行一键）</label>
            <textarea v-model="acForm.props" rows="10" class="input w-full font-mono text-xs" placeholder="gateway.endpoint=...&#10;source.type=mysql" :disabled="acFormSaving"></textarea>
          </div>
          <div class="col-span-2">
            <label class="block text-xs text-gray-500 mb-1">
              secrets（密钥，每行 key=value{{ acFormMode === 'create' ? '（至少含 gateway.api-key）' : '（留空 = 保留既有；填写即整体轮换）' }}）
            </label>
            <textarea v-model="acForm.secrets" rows="4" class="input w-full font-mono text-xs" :placeholder="acFormMode === 'create' ? 'gateway.api-key=pk_...' : 'gateway.api-key=（留空 = 保留既有）'" :disabled="acFormSaving"></textarea>
          </div>
          <div class="flex items-center mt-5">
            <input v-model="acForm.enabled" type="checkbox" class="mr-2" :disabled="acFormSaving" />
            <label class="text-xs text-gray-500">启用（停用后 Agent 拉取回落本地文件）</label>
          </div>
        </div>
        <p class="text-xs text-gray-400 mt-3">
          密钥值以 AES-GCM 加密托管，任何接口只回显键名；game.id / game.environment / status.source-key 由 Control 按行注入，勿写入
        </p>
        <p v-if="acFormError" class="text-xs text-red-600 mt-2">{{ acFormError }}</p>
        <div class="flex justify-end gap-3 mt-5">
          <button @click="acFormOpen = false" class="btn btn-secondary" :disabled="acFormSaving">取消</button>
          <button @click="submitAgentConfigForm" class="btn btn-primary" :disabled="acFormSaving">
            {{ acFormSaving ? '保存中...' : acFormMode === 'create' ? '创建' : '保存' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 创建/编辑 Pull 源弹层（credential 建档必填；编辑留空=保留既有凭证） -->
    <div v-if="formOpen" class="fixed inset-0 z-50 flex items-center justify-center bg-black/40" @click.self="formOpen = false">
      <div class="bg-white rounded-lg shadow-xl p-6 w-full max-w-lg mx-4 max-h-[90vh] overflow-y-auto">
        <div class="flex items-center justify-between mb-4">
          <h3 class="text-base font-medium text-gray-900">
            {{ formMode === 'create' ? '新建 HTTP Pull 源' : `编辑：${form.sourceKey}（${form.environment}）` }}
          </h3>
          <button @click="formOpen = false" class="text-gray-400 hover:text-gray-600 text-sm">关闭</button>
        </div>
        <div class="grid grid-cols-2 gap-3">
          <div>
            <label class="block text-xs text-gray-500 mb-1">环境（environment，须属于该游戏）</label>
            <input v-model="form.environment" class="input w-full" list="pull-env-names" :disabled="formSaving" />
            <datalist id="pull-env-names">
              <option v-for="n in envNames" :key="n" :value="n" />
            </datalist>
          </div>
          <div>
            <label class="block text-xs text-gray-500 mb-1">源标识（sourceKey，同环境内唯一）</label>
            <input v-model="form.sourceKey" class="input w-full font-mono" :disabled="formSaving" />
          </div>
          <div>
            <label class="block text-xs text-gray-500 mb-1">维度类型</label>
            <select v-model="form.dimType" class="input w-full" :disabled="formSaving">
              <option v-for="t in DIM_TYPES" :key="t.value" :value="t.value">{{ t.label }}</option>
            </select>
          </div>
          <div>
            <label class="block text-xs text-gray-500 mb-1">拉取间隔（秒，10~86400）</label>
            <input v-model.number="form.intervalSeconds" type="number" min="10" max="86400" class="input w-full" :disabled="formSaving" />
          </div>
          <div class="col-span-2">
            <label class="block text-xs text-gray-500 mb-1">游戏方 API 地址（endpoint，http/https）</label>
            <input v-model="form.endpoint" class="input w-full font-mono" placeholder="https://api.example.com/v1/items" :disabled="formSaving" />
          </div>
          <div class="col-span-2">
            <label class="block text-xs text-gray-500 mb-1">
              Bearer 凭证{{ formMode === 'create' ? '（必填，加密托管）' : '（留空 = 保留既有凭证）' }}
            </label>
            <input v-model="form.credential" class="input w-full font-mono" type="password" autocomplete="new-password" :disabled="formSaving" />
          </div>
          <div>
            <label class="block text-xs text-gray-500 mb-1">单页条数上限（1~5000）</label>
            <input v-model.number="form.pageLimit" type="number" min="1" max="5000" class="input w-full" :disabled="formSaving" />
          </div>
          <div class="flex items-center mt-5">
            <input v-model="form.enabled" type="checkbox" class="mr-2" :disabled="formSaving" />
            <label class="text-xs text-gray-500">启用（停用后不再调度拉取）</label>
          </div>
        </div>
        <p class="text-xs text-gray-400 mt-3">
          凭证仅入库时可见（AES-GCM 加密托管，任何接口不回显）；断点游标推送成功才前进，失败自动按旧断点重放
        </p>
        <p v-if="formError" class="text-xs text-red-600 mt-2">{{ formError }}</p>
        <div class="flex justify-end gap-3 mt-5">
          <button @click="formOpen = false" class="btn btn-secondary" :disabled="formSaving">取消</button>
          <button @click="submitPullForm" class="btn btn-primary" :disabled="formSaving">
            {{ formSaving ? '保存中...' : formMode === 'create' ? '创建' : '保存' }}
          </button>
        </div>
      </div>
    </div>

    <!-- YAML 导入/导出弹层（导出凭证永不落盘；导入按 (环境, 源标识) 定位，credential 缺省=保留既有） -->
    <div v-if="yamlOpen" class="fixed inset-0 z-50 flex items-center justify-center bg-black/40" @click.self="yamlOpen = false">
      <div class="bg-white rounded-lg shadow-xl p-6 w-full max-w-2xl mx-4 max-h-[90vh] overflow-y-auto">
        <div class="flex items-center justify-between mb-4">
          <h3 class="text-base font-medium text-gray-900">
            {{ yamlMode === 'export' ? '导出 Pull 配置 YAML' : '导入 Pull 配置 YAML' }}
          </h3>
          <button @click="yamlOpen = false" class="text-gray-400 hover:text-gray-600 text-sm">关闭</button>
        </div>
        <template v-if="yamlMode === 'export'">
          <p class="text-xs text-gray-400 mb-2">凭证不导出（导入时 credential 缺省=保留既有）；可编辑后整份粘回「导入 YAML」批量更新</p>
          <textarea v-model="yamlText" rows="16" readonly class="input w-full font-mono text-xs" :disabled="yamlBusy"></textarea>
        </template>
        <template v-else>
          <p class="text-xs text-gray-400 mb-2">dimensions 映射键即 sourceKey；任一条非法整体不生效（事务回滚）</p>
          <textarea v-model="yamlText" rows="16" class="input w-full font-mono text-xs" placeholder="dimensions:&#10;  items:&#10;    environment: prod&#10;    endpoint: https://api.example.com/v1/items&#10;    credential: bearer-token" :disabled="yamlBusy"></textarea>
        </template>
        <p v-if="yamlError" class="text-xs text-red-600 mt-2">{{ yamlError }}</p>
        <div class="flex justify-end gap-3 mt-5" v-if="yamlMode === 'import'">
          <button @click="yamlOpen = false" class="btn btn-secondary" :disabled="yamlBusy">取消</button>
          <button @click="doImport" class="btn btn-primary" :disabled="yamlBusy">{{ yamlBusy ? '导入中...' : '导入' }}</button>
        </div>
      </div>
    </div>
  </div>
</template>
