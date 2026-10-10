<script setup>
import { ref, onMounted } from 'vue'
import api from '@/services/api'
import GameSelector from '@/components/GameSelector.vue'
import { useGameList } from '@/composables/useGameList'

const { currentGameId, loadGames } = useGameList()

const schemas = ref([])
const loading = ref(false)
const selectedSchema = ref(null)
const selectedEvents = ref([])
const compatResult = ref(null)

// PII 策略列（环境级 Schema > ApiKey > 网关默认链的设置入口）：DRAFT 版可编辑
const piiModal = ref(null)
const piiSaving = ref(false)
const piiError = ref('')

const PII_MODES = ['allow', 'mask', 'drop']
const PII_IP_MODES = ['allow', 'coarse', 'drop']

function parsePiiPolicy(raw) {
  if (!raw) return null
  try {
    const obj = JSON.parse(raw)
    return obj && typeof obj === 'object' && !Array.isArray(obj) ? obj : null
  } catch (e) {
    return null
  }
}

function piiChips(schema) {
  const p = parsePiiPolicy(schema.piiPolicy)
  if (!p) return []
  const chips = []
  const modeLabel = { allow: '放行', mask: '脱敏', drop: '剔除', coarse: '粗化' }
  for (const [key, label] of [['email', '邮箱'], ['phone', '手机'], ['ip', 'IP']]) {
    if (typeof p[key] === 'string' && p[key]) chips.push(`${label}:${modeLabel[p[key]] || p[key]}`)
  }
  if (Array.isArray(p.denyKeys) && p.denyKeys.length) chips.push(`拦截×${p.denyKeys.length}`)
  if (Array.isArray(p.maskKeys) && p.maskKeys.length) chips.push(`脱敏键×${p.maskKeys.length}`)
  return chips
}

function openPiiModal(schema) {
  const p = parsePiiPolicy(schema.piiPolicy) || {}
  piiModal.value = {
    schema,
    email: PII_MODES.includes(p.email) ? p.email : '',
    phone: PII_MODES.includes(p.phone) ? p.phone : '',
    ip: PII_IP_MODES.includes(p.ip) ? p.ip : '',
    denyKeys: Array.isArray(p.denyKeys) ? p.denyKeys.join(',') : '',
    maskKeys: Array.isArray(p.maskKeys) ? p.maskKeys.join(',') : ''
  }
  piiError.value = ''
}

function toKeyList(text) {
  return (text || '').split(',').map((s) => s.trim()).filter((s) => s.length > 0)
}

async function savePiiModal() {
  const m = piiModal.value
  const policy = {}
  if (m.email) policy.email = m.email
  if (m.phone) policy.phone = m.phone
  if (m.ip) policy.ip = m.ip
  const denyKeys = toKeyList(m.denyKeys)
  const maskKeys = toKeyList(m.maskKeys)
  if (denyKeys.length) policy.denyKeys = denyKeys
  if (maskKeys.length) policy.maskKeys = maskKeys
  // 全空 = 清除策略（后端 updateEntity 对非 null 值直接覆盖，空串即清）
  const piiPolicy = Object.keys(policy).length ? JSON.stringify(policy) : ''
  piiSaving.value = true
  piiError.value = ''
  try {
    await api.put(`/api/games/${currentGameId.value}/schemas/${m.schema.id}`, {
      name: m.schema.name,
      piiPolicy
    })
    piiModal.value = null
    await loadSchemas()
  } catch (error) {
    console.error('Failed to save pii policy:', error)
    piiError.value = error.response?.data?.message || '保存失败'
  } finally {
    piiSaving.value = false
  }
}

onMounted(async () => {
  await loadGames()
  if (currentGameId.value) {
    await loadSchemas()
  }
})

async function loadSchemas() {
  if (!currentGameId.value) return
  loading.value = true
  selectedSchema.value = null
  selectedEvents.value = []
  compatResult.value = null
  try {
    const response = await api.get(`/api/games/${currentGameId.value}/schemas`)
    schemas.value = response.data.data || []
  } catch (error) {
    console.error('Failed to load schemas:', error)
  } finally {
    loading.value = false
  }
}

function onGameChange() {
  loadSchemas()
}

async function viewEvents(schema) {
  if (selectedSchema.value?.id === schema.id) {
    selectedSchema.value = null
    selectedEvents.value = []
    return
  }
  try {
    const response = await api.get(
      `/api/games/${currentGameId.value}/schemas/${schema.id}/events`)
    selectedSchema.value = schema
    selectedEvents.value = response.data.data || []
  } catch (error) {
    console.error('Failed to load events:', error)
  }
}

// B7 §2.3 版本发布：activate + 兼容门（compatibility≠NONE 时后端先做基线兼容检查）
async function publishSchema(schema) {
  try {
    await api.post(
      `/api/games/${currentGameId.value}/schemas/${schema.id}/publish?userId=system`)
    compatResult.value = null
    await loadSchemas()
  } catch (error) {
    console.error('Failed to publish schema:', error)
    alert('发布失败: ' + (error.response?.data?.message || error.message))
  }
}

async function checkCompatibility(schema) {
  try {
    const response = await api.get(
      `/api/games/${currentGameId.value}/schemas/${schema.id}/compatibility`)
    compatResult.value = response.data.data
  } catch (error) {
    console.error('Failed to check compatibility:', error)
    alert('兼容检查失败: ' + (error.response?.data?.message || error.message))
  }
}

async function deactivateSchema(schema) {
  if (!confirm(`确定要弃用 Schema「${schema.name}」吗？`)) return
  try {
    await api.post(
      `/api/games/${currentGameId.value}/schemas/${schema.id}/deactivate`)
    await loadSchemas()
  } catch (error) {
    console.error('Failed to deactivate schema:', error)
    alert('弃用失败: ' + (error.response?.data?.message || error.message))
  }
}

async function deleteSchema(schema) {
  if (!confirm('确定要删除这个 Schema 吗？')) return
  try {
    await api.delete(
      `/api/games/${currentGameId.value}/schemas/${schema.id}`)
    await loadSchemas()
  } catch (error) {
    console.error('Failed to delete schema:', error)
    alert('删除失败: ' + (error.response?.data?.message || error.message))
  }
}

function getStatusLabel(status) {
  const labels = { DRAFT: '草稿', ACTIVE: '活跃', DEPRECATED: '已弃用' }
  return labels[status] || status
}

function getStatusColor(status) {
  const colors = {
    DRAFT: 'bg-yellow-100 text-yellow-800',
    ACTIVE: 'bg-green-100 text-green-800',
    DEPRECATED: 'bg-gray-100 text-gray-800'
  }
  return colors[status] || 'bg-gray-100 text-gray-800'
}

function getCompatLabel(mode) {
  const labels = {
    NONE: '不检查',
    BACKWARD: '向后兼容',
    FORWARD: '向前兼容',
    FULL: '完全兼容'
  }
  return labels[mode] || mode || '不检查'
}
</script>

<template>
  <div>
    <div class="sm:flex sm:items-center mb-8">
      <div class="sm:flex-auto">
        <h1 class="text-2xl font-bold text-gray-900">事件 Schema</h1>
        <p class="mt-1 text-sm text-gray-500">
          EventSchema 版本管理：发布、兼容检查与事件定义
        </p>
      </div>
    </div>

    <div class="mb-6">
      <GameSelector @change="onGameChange" />
    </div>

    <div v-if="!currentGameId" class="text-center py-12">
      <h3 class="mt-2 text-sm font-medium text-gray-900">请选择一个游戏</h3>
      <p class="mt-1 text-sm text-gray-500">事件 Schema 按游戏维度管理</p>
    </div>

    <div v-else-if="loading" class="text-center py-12">
      <div class="animate-spin rounded-full h-12 w-12 border-b-2 border-primary-600 mx-auto"></div>
      <p class="mt-4 text-gray-500">加载中...</p>
    </div>

    <div v-else-if="schemas.length === 0" class="text-center py-12">
      <h3 class="mt-2 text-sm font-medium text-gray-900">暂无事件 Schema</h3>
      <p class="mt-1 text-sm text-gray-500">通过 API 创建一个 Schema 版本来开始</p>
    </div>

    <template v-else>
      <div class="card">
        <div class="overflow-x-auto">
          <table class="table">
            <thead>
              <tr>
                <th>名称 / 版本</th>
                <th>环境</th>
                <th>状态</th>
                <th>兼容策略</th>
                <th>未知事件拒收</th>
                <th>PII 策略</th>
                <th>事件数</th>
                <th>发布时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="schema in schemas" :key="schema.id">
                <td>
                  <div class="font-medium">{{ schema.name }}</div>
                  <div v-if="schema.version" class="text-xs text-gray-500">v{{ schema.version }}</div>
                </td>
                <td>{{ schema.environmentId || '全局' }}</td>
                <td>
                  <span :class="[getStatusColor(schema.status), 'badge']">
                    {{ getStatusLabel(schema.status) }}
                  </span>
                </td>
                <td>{{ getCompatLabel(schema.compatibility) }}</td>
                <td>
                  <span :class="schema.rejectUnknownEvents ? 'bg-green-100 text-green-800' : 'bg-gray-100 text-gray-800'" class="badge">
                    {{ schema.rejectUnknownEvents ? '拒收' : '放行' }}
                  </span>
                </td>
                <td>
                  <template v-if="piiChips(schema).length">
                    <span
                      v-for="chip in piiChips(schema)"
                      :key="chip"
                      class="badge bg-blue-100 text-blue-800 mr-1"
                    >{{ chip }}</span>
                  </template>
                  <span v-else class="badge bg-gray-100 text-gray-500">未设</span>
                </td>
                <td>{{ schema.activeEvents }}/{{ schema.totalEvents }}</td>
                <td>{{ schema.activatedAt ? new Date(schema.activatedAt).toLocaleDateString('zh-CN') : '-' }}</td>
                <td>
                  <div class="flex items-center space-x-3">
                    <button
                      v-if="schema.status === 'DRAFT'"
                      @click="publishSchema(schema)"
                      class="text-primary-600 hover:text-primary-700"
                    >
                      发布
                    </button>
                    <button
                      v-if="schema.status === 'DRAFT'"
                      @click="openPiiModal(schema)"
                      class="text-purple-600 hover:text-purple-700"
                    >
                      PII 策略
                    </button>
                    <button
                      @click="checkCompatibility(schema)"
                      class="text-blue-600 hover:text-blue-700"
                    >
                      兼容检查
                    </button>
                    <button
                      @click="viewEvents(schema)"
                      class="text-gray-600 hover:text-gray-700"
                    >
                      {{ selectedSchema?.id === schema.id ? '收起事件' : '查看事件' }}
                    </button>
                    <button
                      v-if="schema.status === 'ACTIVE'"
                      @click="deactivateSchema(schema)"
                      class="text-yellow-600 hover:text-yellow-700"
                    >
                      弃用
                    </button>
                    <button
                      v-if="schema.status === 'DRAFT'"
                      @click="deleteSchema(schema)"
                      class="text-red-600 hover:text-red-700"
                    >
                      删除
                    </button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- 事件清单：跟随所选 Schema 展开 -->
      <div v-if="selectedSchema" class="card mt-6">
        <h3 class="text-sm font-medium text-gray-900 mb-4">
          「{{ selectedSchema.name }}」事件定义（{{ selectedEvents.length }}）
        </h3>
        <p v-if="selectedEvents.length === 0" class="text-sm text-gray-500">该 Schema 暂无事件定义</p>
        <div v-else class="overflow-x-auto">
          <table class="table">
            <thead>
              <tr>
                <th>事件名</th>
                <th>类型</th>
                <th>重要性</th>
                <th>状态</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="event in selectedEvents" :key="event.id">
                <td class="font-medium">{{ event.eventName }}</td>
                <td>{{ event.eventType }}</td>
                <td>{{ event.importance }}</td>
                <td>{{ event.status }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- 兼容检查结果：B7 §2.3 diff 三分类 -->
      <div v-if="compatResult" class="card mt-6">
        <h3 class="text-sm font-medium text-gray-900 mb-4">兼容检查结果</h3>
        <div class="grid grid-cols-2 gap-4 text-sm">
          <div>
            策略：<span class="font-medium">{{ getCompatLabel(compatResult.mode) }}</span>
          </div>
          <div>
            结论：
            <span :class="compatResult.compatible ? 'text-green-600' : 'text-red-600'" class="font-medium">
              {{ compatResult.compatible ? '兼容' : '不兼容' }}
            </span>
          </div>
          <div class="col-span-2">
            基线版本：{{ compatResult.baselineId || '无（首个版本恒兼容）' }}
          </div>
          <div v-if="compatResult.addedEvents?.length" class="col-span-2">
            新增事件：{{ compatResult.addedEvents.join('、') }}
          </div>
          <div v-if="compatResult.removedEvents?.length" class="col-span-2 text-red-600">
            下线事件（BACKWARD 违例）：{{ compatResult.removedEvents.join('、') }}
          </div>
          <div v-if="compatResult.changedEvents?.length" class="col-span-2">
            签名变化事件（信息项）：{{ compatResult.changedEvents.join('、') }}
          </div>
        </div>
      </div>
    </template>

    <!-- PII 策略编辑：仅 DRAFT 可改（生效链路 环境级 Schema > ApiKey > 网关默认） -->
    <div
      v-if="piiModal"
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/40"
      @click.self="piiModal = null"
    >
      <div class="bg-white rounded-lg shadow-xl p-6 w-full max-w-lg mx-4 max-h-[90vh] overflow-y-auto">
        <div class="flex items-center justify-between mb-4">
          <h3 class="text-base font-medium text-gray-900">PII 策略：{{ piiModal.schema.name }}</h3>
          <button @click="piiModal = null" class="text-gray-400 hover:text-gray-600 text-sm">关闭</button>
        </div>
        <p class="text-xs text-gray-500 mb-4">
          环境级 Schema 策略压在 ApiKey 级与网关默认之上；留空（不指定）的字段回落下一层。
          名单为逗号分隔的属性键。改动随版本发布生效。
        </p>
        <div v-if="piiError" class="mb-4 rounded border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {{ piiError }}
        </div>
        <div class="grid grid-cols-3 gap-4 mb-4">
          <div>
            <label class="block text-xs text-gray-500 mb-1">邮箱</label>
            <select v-model="piiModal.email" class="input w-full">
              <option value="">不指定</option>
              <option v-for="mode in PII_MODES" :key="mode" :value="mode">{{ mode }}</option>
            </select>
          </div>
          <div>
            <label class="block text-xs text-gray-500 mb-1">手机号</label>
            <select v-model="piiModal.phone" class="input w-full">
              <option value="">不指定</option>
              <option v-for="mode in PII_MODES" :key="mode" :value="mode">{{ mode }}</option>
            </select>
          </div>
          <div>
            <label class="block text-xs text-gray-500 mb-1">IP</label>
            <select v-model="piiModal.ip" class="input w-full">
              <option value="">不指定</option>
              <option v-for="mode in PII_IP_MODES" :key="mode" :value="mode">{{ mode }}</option>
            </select>
          </div>
        </div>
        <div class="mb-4">
          <label class="block text-xs text-gray-500 mb-1">拦截名单（denyKeys，逗号分隔，命中即拒收 pii_blocked）</label>
          <input v-model="piiModal.denyKeys" class="input w-full" placeholder="raw_email, id_card" />
        </div>
        <div class="mb-6">
          <label class="block text-xs text-gray-500 mb-1">脱敏名单（maskKeys，逗号分隔，命中即打码）</label>
          <input v-model="piiModal.maskKeys" class="input w-full" placeholder="contact, nickname" />
        </div>
        <div class="flex justify-end space-x-3">
          <button @click="piiModal = null" class="btn btn-secondary">取消</button>
          <button :disabled="piiSaving" @click="savePiiModal" class="btn btn-primary">
            {{ piiSaving ? '保存中...' : '保存' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
