<script setup>
import { ref, watch, onMounted } from 'vue'
import api from '@/services/api'
import GameSelector from '@/components/GameSelector.vue'
import { useGameList } from '@/composables/useGameList'

const { currentGameId } = useGameList()

const cases = ref([])
const loading = ref(false)
const error = ref('')
const filterStatus = ref('')
const filterLevel = ref('')

// 详情弹层
const detail = ref(null)
const detailLoading = ref(false)
const showUnblock = ref(false)
const unblockReason = ref('')
const unblocking = ref(false)

const statusLabels = {
  OPEN: '已建案',
  REVIEW: '人工审核',
  ALERT: '告警',
  MARK: '打标',
  THROTTLE: '限流',
  BLOCK: '封禁',
  RESOLVED: '已结案'
}
const statusColors = {
  OPEN: 'bg-gray-100 text-gray-700',
  REVIEW: 'bg-indigo-100 text-indigo-700',
  ALERT: 'bg-yellow-100 text-yellow-700',
  MARK: 'bg-purple-100 text-purple-700',
  THROTTLE: 'bg-orange-100 text-orange-700',
  BLOCK: 'bg-red-100 text-red-700',
  RESOLVED: 'bg-green-100 text-green-700'
}
const levelLabels = { LOW: '低', MEDIUM: '中', HIGH: '高', CRITICAL: '严重' }
const levelColors = {
  LOW: 'bg-gray-100 text-gray-700',
  MEDIUM: 'bg-blue-100 text-blue-700',
  HIGH: 'bg-orange-100 text-orange-700',
  CRITICAL: 'bg-red-600 text-white'
}
const executionLabels = {
  PENDING: '待执行',
  EXECUTED: '已执行',
  FAILED: '执行失败',
  CANCELLED: '已取消',
  APPEALED: '已申诉'
}
const dispositionLabels = {
  confirmed_benign: '误杀（确认正常）',
  confirmed_fraud: '确认违规',
  inconclusive: '证据不足'
}

async function load() {
  if (!currentGameId.value) return
  loading.value = true
  error.value = ''
  try {
    const params = { limit: 100 }
    if (filterStatus.value) params.status = filterStatus.value
    if (filterLevel.value) params.riskLevel = filterLevel.value
    const res = await api.get(`/api/games/${currentGameId.value}/risk-cases`, { params })
    cases.value = res.data
  } catch (e) {
    error.value = e.response?.data?.message || '加载风控案例失败'
    console.error('Failed to load risk cases:', e)
  } finally {
    loading.value = false
  }
}

async function openDetail(rc) {
  detailLoading.value = true
  showUnblock.value = false
  unblockReason.value = ''
  try {
    const res = await api.get(`/api/games/${currentGameId.value}/risk-cases/${rc.id}`)
    detail.value = res.data
  } catch (e) {
    error.value = e.response?.data?.message || '加载案例详情失败'
  } finally {
    detailLoading.value = false  }
}

function canUnblock(c) {
  return c.actionTaken === 'BLOCK' && c.executionStatus === 'EXECUTED' && !c.unblockedAt
}

async function submitUnblock() {
  if (!unblockReason.value.trim()) {
    error.value = '请填写解除原因'
    return
  }
  unblocking.value = true
  error.value = ''
  try {
    await api.post(
      `/api/games/${currentGameId.value}/risk-cases/${detail.value.id}/unblock`,
      { reason: unblockReason.value.trim() }
    )
    detail.value = null
    showUnblock.value = false
    unblockReason.value = ''
    await load()
  } catch (e) {
    error.value = e.response?.data?.message || '解除封禁失败'
  } finally {
    unblocking.value = false
  }
}

function prettyJson(v) {
  if (v == null) return ''
  return typeof v === 'string' ? v : JSON.stringify(v, null, 2)
}

function fmtTime(t) {
  return t ? new Date(t).toLocaleString('zh-CN') : '—'
}

watch(currentGameId, load)
watch([filterStatus, filterLevel], load)
onMounted(load)
</script>

<template>
  <div>
    <div class="sm:flex sm:items-center mb-6">
      <div class="sm:flex-auto">
        <h1 class="text-2xl font-bold text-gray-900">风控案例</h1>
        <p class="mt-1 text-sm text-gray-500">
          误杀漏杀回看：案例列表、证据上下文与人工解除封禁
        </p>
      </div>
      <div class="mt-4 sm:mt-0 sm:flex sm:items-center gap-2">
        <GameSelector />
        <select v-model="filterStatus" class="input">
          <option value="">全部状态</option>
          <option v-for="(label, key) in statusLabels" :key="key" :value="key">{{ label }}</option>
        </select>
        <select v-model="filterLevel" class="input">
          <option value="">全部等级</option>
          <option v-for="(label, key) in levelLabels" :key="key" :value="key">{{ label }}</option>
        </select>
        <button @click="load" class="btn btn-secondary" :disabled="loading">刷新</button>
      </div>
    </div>

    <p v-if="error" class="mb-4 text-sm text-red-600">{{ error }}</p>

    <div v-if="loading" class="text-center py-12">
      <div class="animate-spin rounded-full h-12 w-12 border-b-2 border-primary-600 mx-auto"></div>
      <p class="mt-4 text-gray-500">加载中...</p>
    </div>

    <div v-else-if="cases.length === 0" class="text-center py-12">
      <h3 class="mt-2 text-sm font-medium text-gray-900">暂无风控案例</h3>
      <p class="mt-1 text-sm text-gray-500">风控规则触发后将在此建案，可回看证据并处置</p>
    </div>

    <div v-else class="card">
      <div class="overflow-x-auto">
        <table class="table">
          <thead>
            <tr>
              <th>案例编号</th>
              <th>等级</th>
              <th>判定状态</th>
              <th>目标</th>
              <th>动作 / 执行</th>
              <th>处置结论</th>
              <th>创建时间</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="rc in cases" :key="rc.id">
              <td class="font-medium">{{ rc.caseNumber }}</td>
              <td>
                <span :class="[levelColors[rc.riskLevel] || 'bg-gray-100 text-gray-700', 'badge']">
                  {{ levelLabels[rc.riskLevel] || rc.riskLevel }}
                  <template v-if="rc.riskScore != null">· {{ rc.riskScore }}</template>
                </span>
              </td>
              <td>
                <span :class="[statusColors[rc.status] || 'bg-gray-100 text-gray-700', 'badge']">
                  {{ statusLabels[rc.status] || rc.status }}
                </span>
              </td>
              <td>
                <div class="text-sm">{{ rc.targetName || rc.targetId }}</div>
                <div class="text-xs text-gray-400">{{ rc.targetType }} · {{ rc.targetId }}</div>
              </td>
              <td class="text-sm">
                {{ rc.actionTaken }}
                <span class="text-gray-400">/</span>
                {{ executionLabels[rc.executionStatus] || rc.executionStatus }}
              </td>
              <td class="text-sm">{{ dispositionLabels[rc.disposition] || rc.disposition || '—' }}</td>
              <td class="text-sm text-gray-500">{{ fmtTime(rc.createdAt) }}</td>
              <td>
                <button @click="openDetail(rc)" class="text-sm text-primary-600 hover:underline">详情</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 详情弹层 -->
    <div v-if="detailLoading" class="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
      <div class="animate-spin rounded-full h-12 w-12 border-b-2 border-white"></div>
    </div>
    <div v-else-if="detail" class="fixed inset-0 z-50 flex items-center justify-center bg-black/40" @click.self="detail = null">
      <div class="bg-white rounded-lg shadow-xl p-6 w-full max-w-2xl mx-4 max-h-[85vh] overflow-y-auto">
        <div class="flex items-start justify-between mb-4">
          <div>
            <h3 class="text-base font-medium text-gray-900">{{ detail.caseNumber }}</h3>
            <p class="text-sm text-gray-500 mt-1">
              <span :class="[levelColors[detail.riskLevel] || 'bg-gray-100 text-gray-700', 'badge']">{{ levelLabels[detail.riskLevel] || detail.riskLevel }}</span>
              <span :class="[statusColors[detail.status] || 'bg-gray-100 text-gray-700', 'badge']">{{ statusLabels[detail.status] || detail.status }}</span>
              <span class="badge bg-gray-100 text-gray-700">{{ detail.actionTaken }}</span>
            </p>
          </div>
          <button @click="detail = null" class="text-gray-400 hover:text-gray-600">✕</button>
        </div>

        <dl class="grid grid-cols-2 gap-x-4 gap-y-2 text-sm mb-4">
          <div><dt class="text-gray-500 inline">目标：</dt><span class="font-medium">{{ detail.targetType }} {{ detail.targetId }}</span><span v-if="detail.targetName" class="text-gray-500">（{{ detail.targetName }}）</span></div>
          <div><dt class="text-gray-500 inline">环境：</dt>{{ detail.environmentId || '—' }}</div>
          <div><dt class="text-gray-500 inline">触发事件：</dt>{{ detail.triggerEventType || '—' }}<template v-if="detail.triggerEventName"> / {{ detail.triggerEventName }}</template></div>
          <div><dt class="text-gray-500 inline">规则：</dt>{{ detail.riskRuleId || '—' }}</div>
          <div><dt class="text-gray-500 inline">执行：</dt>{{ executionLabels[detail.executionStatus] || detail.executionStatus }}<template v-if="detail.executedAt"> · {{ fmtTime(detail.executedAt) }}</template></div>
          <div><dt class="text-gray-500 inline">执行失败原因：</dt>{{ detail.executionError || '—' }}</div>
          <div><dt class="text-gray-500 inline">处置结论：</dt>{{ dispositionLabels[detail.disposition] || detail.disposition || '—' }}</div>
          <div><dt class="text-gray-500 inline">审核状态：</dt>{{ detail.reviewStatus || '—' }}</div>
          <div class="col-span-2"><dt class="text-gray-500 inline">审核备注：</dt>{{ detail.reviewNotes || '—' }}<template v-if="detail.reviewedBy">（{{ detail.reviewedBy }} · {{ fmtTime(detail.reviewedAt) }}）</template></div>
          <div class="col-span-2"><dt class="text-gray-500 inline">建案时间：</dt>{{ fmtTime(detail.createdAt) }}<template v-if="detail.resolvedAt"> · 结案：{{ fmtTime(detail.resolvedAt) }}</template></div>
          <div v-if="detail.unblockedAt" class="col-span-2 text-green-700">
            已于 {{ fmtTime(detail.unblockedAt) }} 由 {{ detail.unblockedBy }} 解除封禁：{{ detail.unblockReason }}
          </div>
        </dl>

        <div v-if="detail.evidence" class="mb-4">
          <h4 class="text-sm font-medium text-gray-700 mb-1">证据</h4>
          <pre class="bg-gray-50 rounded p-3 text-xs overflow-x-auto whitespace-pre-wrap">{{ prettyJson(detail.evidence) }}</pre>
        </div>
        <div v-if="detail.context" class="mb-4">
          <h4 class="text-sm font-medium text-gray-700 mb-1">上下文</h4>
          <pre class="bg-gray-50 rounded p-3 text-xs overflow-x-auto whitespace-pre-wrap">{{ prettyJson(detail.context) }}</pre>
        </div>

        <!-- 误杀处置：人工解除封禁（联动释放封禁名单记录） -->
        <div v-if="canUnblock(detail)" class="border-t pt-4">
          <button v-if="!showUnblock" @click="showUnblock = true" class="btn btn-secondary">
            解除封禁（误杀处置）
          </button>
          <div v-else>
            <label class="block text-sm font-medium text-gray-700 mb-1">解除原因（必填，联动释放由本案例创建的封禁名单记录）</label>
            <textarea v-model="unblockReason" rows="2" class="input mb-3" placeholder="如：已核实为正常玩家，规则误报"></textarea>
            <div class="flex justify-end gap-3">
              <button @click="showUnblock = false" class="btn btn-secondary">取消</button>
              <button @click="submitUnblock" class="btn btn-primary" :disabled="unblocking">
                {{ unblocking ? '解除中...' : '确认解除' }}
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
