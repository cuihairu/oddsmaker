<script setup>
import { ref, reactive, watch, onMounted } from 'vue'
import api from '@/services/api'
import GameSelector from '@/components/GameSelector.vue'
import { useGameList } from '@/composables/useGameList'

const { currentGameId } = useGameList()

const requests = ref([])
const loading = ref(false)
const error = ref('')
const showForm = ref(false)
const saving = ref(false)
const lastCreated = ref(null)      // 创建后展示 resolved_identities 折叠面板
const detailTarget = ref(null)     // execution_summary 详情弹层

const typeOptions = [
  { value: 'PLAYER_ID', label: 'player_id（玩家 ID）' },
  { value: 'USER_ID', label: 'user_id（用户 ID）' },
  { value: 'DEVICE_ID', label: 'device_id（设备 ID）' }
]
const typeLabels = { PLAYER_ID: 'player_id', USER_ID: 'user_id', DEVICE_ID: 'device_id' }
const statusColors = {
  PENDING: 'bg-blue-100 text-blue-700',
  PROCESSING: 'bg-yellow-100 text-yellow-700',
  COMPLETED: 'bg-green-100 text-green-700',
  PARTIAL: 'bg-orange-100 text-orange-700',
  FAILED: 'bg-red-100 text-red-700',
  CANCELLED: 'bg-gray-100 text-gray-500'
}

const form = reactive({
  requestType: 'PLAYER_ID',
  requestValue: '',
  scheduledFor: ''
})

function resetForm() {
  form.requestType = 'PLAYER_ID'
  form.requestValue = ''
  form.scheduledFor = ''
}

async function load() {
  if (!currentGameId.value) return
  loading.value = true
  error.value = ''
  lastCreated.value = null
  try {
    const res = await api.get('/api/privacy/erasure-requests', {
      params: { gameId: currentGameId.value }
    })
    requests.value = res.data
  } catch (e) {
    error.value = e.response?.data || '加载删除请求失败'
    console.error('Failed to load erasure requests:', e)
  } finally {
    loading.value = false
  }
}

async function createRequest() {
  if (!form.requestValue.trim()) {
    error.value = '请填写标识值'
    return
  }
  saving.value = true
  error.value = ''
  try {
    const res = await api.post('/api/privacy/erasure-requests', {
      gameId: currentGameId.value,
      requestType: form.requestType,
      requestValue: form.requestValue.trim(),
      scheduledFor: form.scheduledFor || null
    })
    lastCreated.value = res.data
    showForm.value = false
    resetForm()
    await load()
  } catch (e) {
    error.value = e.response?.data || '创建删除请求失败'
  } finally {
    saving.value = false
  }
}

async function cancelRequest(req) {
  if (!confirm(`确认取消删除请求「${req.id}」？仅 PENDING 状态可取消。`)) return
  try {
    await api.post(`/api/privacy/erasure-requests/${req.id}/cancel`)
    await load()
  } catch (e) {
    error.value = e.response?.data || '取消失败'
  }
}

function openDetail(req) {
  detailTarget.value = req
}

function parseSummary(req) {
  try {
    return JSON.parse(req.executionSummary || '{}')
  } catch {
    return {}
  }
}

function resolvedCounts(req) {
  try {
    const r = JSON.parse(req.resolvedIdentities || '{}')
    return [
      ['身份', r.identityIds], ['用户', r.userIds], ['玩家', r.playerIds],
      ['设备', r.deviceIds], ['角色', r.characterIds]
    ].map(([label, arr]) => `${label} ${(arr || []).length}`).join(' / ') + (r.truncated ? '（已截断）' : '')
  } catch {
    return '-'
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
        <h1 class="text-2xl font-bold text-gray-900">隐私合规</h1>
        <p class="mt-1 text-sm text-gray-500">
          玩家数据删除请求（GDPR erasure）：标识图谱展开后异步清洗——PG 明细硬删 + 风控记录匿名化占位 + ClickHouse 行级 mutation
        </p>
      </div>
      <div class="mt-4 sm:mt-0 sm:ml-16 flex items-center gap-3 flex-wrap">
        <GameSelector />
        <button @click="load" class="btn btn-secondary" :disabled="loading">刷新</button>
        <button @click="showForm = !showForm" class="btn btn-primary">
          {{ showForm ? '收起表单' : '新建删除请求' }}
        </button>
      </div>
    </div>

    <div v-if="error" class="card mb-6 text-sm text-red-600">{{ error }}</div>

    <!-- 新建删除请求表单 -->
    <div v-if="showForm" class="card mb-8">
      <h3 class="text-base font-medium text-gray-900 mb-4">新建删除请求</h3>
      <div class="grid grid-cols-1 md:grid-cols-3 gap-4">
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">标识类型</label>
          <select v-model="form.requestType" class="input">
            <option v-for="t in typeOptions" :key="t.value" :value="t.value">{{ t.label }}</option>
          </select>
        </div>
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">标识值</label>
          <input v-model="form.requestValue" class="input" placeholder="如 p_8f3a2c..." />
        </div>
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">定时执行（留空 = 立即）</label>
          <input v-model="form.scheduledFor" type="datetime-local" class="input" />
        </div>
        <div class="md:col-span-3">
          <p class="text-xs text-gray-400">
            将沿身份图谱展开全部关联标识后执行：明细硬删（登录/支付/兑换/邮件领取/身份图谱/导出文件），
            风控案件与封禁记录匿名化为 erased:&lt;请求ID&gt; 保留审计线索。操作不可逆，请确认合规依据。
          </p>
          <button @click="createRequest" class="btn btn-primary mt-3" :disabled="saving">
            {{ saving ? '提交中...' : '提交删除请求' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 最近创建：resolved_identities 折叠面板 -->
    <div v-if="lastCreated" class="card mb-8">
      <details>
        <summary class="cursor-pointer text-sm font-medium text-gray-900">
          已创建 {{ lastCreated.id }} —— 展开的标识集合
        </summary>
        <div class="mt-3 text-sm">
          <p class="text-gray-600 mb-2">{{ resolvedCounts(lastCreated) }}</p>
          <pre class="bg-gray-50 rounded p-3 text-xs overflow-x-auto max-h-64 whitespace-pre-wrap">{{ lastCreated.resolvedIdentities }}</pre>
          <p
            v-if="(() => { try { return JSON.parse(lastCreated.resolvedIdentities || '{}').truncated } catch { return false } })()"
            class="mt-2 text-xs text-orange-600"
          >
            标识数量超过展开上限，仅覆盖已解析子集——建议分批按剩余标识补充请求
          </p>
        </div>
      </details>
    </div>

    <!-- 请求列表 -->
    <div class="card overflow-x-auto">
      <h3 class="text-base font-medium text-gray-900 mb-4">删除请求（{{ requests.length }}）</h3>
      <table v-if="requests.length" class="min-w-full divide-y divide-gray-200 text-sm">
        <thead>
          <tr class="text-left text-xs text-gray-500 uppercase">
            <th class="py-2 pr-4">请求</th>
            <th class="py-2 pr-4">标识</th>
            <th class="py-2 pr-4">展开规模</th>
            <th class="py-2 pr-4">状态</th>
            <th class="py-2 pr-4">定时</th>
            <th class="py-2 pr-4">创建</th>
            <th class="py-2 pr-4">完成</th>
            <th class="py-2">操作</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-gray-100">
          <tr v-for="req in requests" :key="req.id">
            <td class="py-3 pr-4 font-medium text-gray-900">
              {{ req.id }}
              <p class="text-xs text-gray-400 mt-0.5">by {{ req.requestedBy || '-' }}</p>
            </td>
            <td class="py-3 pr-4 text-gray-600">
              {{ typeLabels[req.requestType] || req.requestType }}<br />
              <span class="text-xs break-all">{{ req.requestValue }}</span>
            </td>
            <td class="py-3 pr-4 text-xs text-gray-500">{{ resolvedCounts(req) }}</td>
            <td class="py-3 pr-4">
              <span class="px-2 py-0.5 rounded-full text-xs font-medium" :class="statusColors[req.status]">
                {{ req.status }}
              </span>
            </td>
            <td class="py-3 pr-4 text-gray-500 text-xs">{{ req.scheduledFor ? fmtTime(req.scheduledFor) : '立即' }}</td>
            <td class="py-3 pr-4 text-gray-500 text-xs">{{ fmtTime(req.createdAt) }}</td>
            <td class="py-3 pr-4 text-gray-500 text-xs">{{ fmtTime(req.completedAt) }}</td>
            <td class="py-3 flex items-center gap-2 whitespace-nowrap">
              <button @click="openDetail(req)" class="text-xs text-blue-600 hover:underline">详情</button>
              <button
                v-if="req.status === 'PENDING'"
                @click="cancelRequest(req)"
                class="text-xs text-red-600 hover:underline"
              >取消</button>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else class="text-sm text-gray-400 py-6 text-center">暂无删除请求</p>
    </div>

    <!-- 详情弹层：execution_summary 全量 JSON -->
    <div
      v-if="detailTarget"
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/40"
      @click.self="detailTarget = null"
    >
      <div class="bg-white rounded-lg shadow-xl p-6 w-full max-w-2xl mx-4 max-h-[80vh] overflow-y-auto">
        <div class="flex items-start justify-between mb-3">
          <h3 class="text-base font-medium text-gray-900">执行详情 {{ detailTarget.id }}</h3>
          <span class="px-2 py-0.5 rounded-full text-xs font-medium" :class="statusColors[detailTarget.status]">
            {{ detailTarget.status }}
          </span>
        </div>
        <div
          v-if="['PARTIAL', 'FAILED'].includes(detailTarget.status) && detailTarget.errorMessage"
          class="mb-3 rounded border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-600"
        >
          {{ detailTarget.errorMessage }}
        </div>
        <p class="text-xs text-gray-500 mb-1">PG 明细删除 + 匿名化行数与 CH 各表 mutation：</p>
        <pre class="bg-gray-50 rounded p-3 text-xs overflow-x-auto whitespace-pre-wrap">{{ parseSummary(detailTarget).ch && parseSummary(detailTarget).ch.confirmed === false ? '（CH mutation 提交已持久化，确认轮询未完成 confirmed=false）\n' : '' }}{{ detailTarget.executionSummary || '（尚未执行）' }}</pre>
        <p class="text-xs text-gray-500 mt-3 mb-1">展开的标识集合：</p>
        <pre class="bg-gray-50 rounded p-3 text-xs overflow-x-auto max-h-56 whitespace-pre-wrap">{{ detailTarget.resolvedIdentities }}</pre>
        <div class="flex justify-end mt-4">
          <button @click="detailTarget = null" class="btn btn-secondary">关闭</button>
        </div>
      </div>
    </div>
  </div>
</template>
