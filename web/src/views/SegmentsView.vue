<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import api from '@/services/api'
import GameSelector from '@/components/GameSelector.vue'
import { useGameList } from '@/composables/useGameList'

const { currentGameId } = useGameList()

const segments = ref([])
const loading = ref(false)
const error = ref('')
const success = ref('')
const busyId = ref(null)

// ---- 创建表单 ----
const showCreate = ref(false)
const creating = ref(false)
const createError = ref('')
const form = ref(emptyForm())

// ---- 成员预览 ----
const previewTarget = ref(null)
const previewMembers = ref([])
const previewLoading = ref(false)
const previewError = ref('')

const subjectLabels = { PLAYER: '玩家', DEVICE: '设备' }
const statusColors = {
  ACTIVE: 'bg-green-100 text-green-700',
  INACTIVE: 'bg-gray-100 text-gray-500'
}
const attributeFields = ['platform', 'country', 'app_version', 'sdk_version', 'server_id', 'game_mode']
const attributeOps = ['eq', 'neq', 'in', 'gte', 'lte']
const eventOps = [
  { value: 'gte', label: '次数 ≥' },
  { value: 'lte', label: '次数 ≤' }
]

function emptyForm() {
  return {
    name: '',
    displayName: '',
    environment: 'prod',
    subject: 'PLAYER',
    match: 'all',
    withinDays: 90,
    conditions: [emptyCondition()]
  }
}

function emptyCondition() {
  return { kind: 'attribute', field: 'platform', op: 'eq', value: '', eventName: '', count: 1, withinDays: null }
}

const canSubmit = computed(() =>
  form.value.name.trim().length > 0 &&
  form.value.conditions.length > 0 &&
  form.value.conditions.every(validCondition)
)

function validCondition(c) {
  if (c.kind === 'attribute') return String(c.value ?? '').trim().length > 0 || Array.isArray(c.value)
  return String(c.eventName ?? '').trim().length > 0 && Number(c.count) > 0
}

// in 多值：逗号分隔 → 数组
function normalizeCondition(c) {
  const out = { kind: c.kind }
  if (c.kind === 'attribute') {
    out.field = c.field
    out.op = c.op
    out.value = c.op === 'in'
      ? String(c.value).split(/[，,]/).map((s) => s.trim()).filter(Boolean)
      : c.value
  } else {
    out.event_name = c.eventName
    out.op = c.op || 'gte'
    out.count = Number(c.count) || 1
    if (c.withinDays) out.within_days = Number(c.withinDays)
  }
  return out
}

async function load() {
  if (!currentGameId.value) return
  loading.value = true
  error.value = ''
  try {
    const res = await api.get(`/api/games/${currentGameId.value}/segments`)
    segments.value = res.data
  } catch (e) {
    error.value = e.response?.data?.message || '加载分群列表失败'
    console.error('Failed to load segments:', e)
  } finally {
    loading.value = false
  }
}

async function create() {
  createError.value = ''
  creating.value = true
  try {
    const definition = {
      match: form.value.match,
      within_days: Number(form.value.withinDays) || 90,
      conditions: form.value.conditions.map(normalizeCondition)
    }
    await api.post(`/api/games/${currentGameId.value}/segments`, {
      name: form.value.name,
      display_name: form.value.displayName || null,
      environment: form.value.environment,
      subject: form.value.subject,
      definition: JSON.stringify(definition)
    })
    success.value = `分群「${form.value.name}」已创建，点击「计算」物化成员`
    showCreate.value = false
    form.value = emptyForm()
    await load()
  } catch (e) {
    createError.value = e.response?.data?.message || '创建失败'
  } finally {
    creating.value = false
  }
}

async function compute(segment) {
  busyId.value = segment.id
  error.value = ''
  success.value = ''
  try {
    const res = await api.post(`/api/segments/${segment.id}/compute`)
    segment.member_count = res.data.memberCount
    segment.last_computed_at = new Date().toISOString()
    success.value = `「${segment.display_name || segment.name}」计算完成：${Number(res.data.memberCount || 0).toLocaleString()} 名成员`
  } catch (e) {
    error.value = e.response?.data?.message || '计算失败'
  } finally {
    busyId.value = null
  }
}

async function toggleStatus(segment) {
  busyId.value = segment.id
  error.value = ''
  try {
    const res = await api.put(`/api/segments/${segment.id}`, {
      status: segment.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
    })
    Object.assign(segment, res.data)
  } catch (e) {
    error.value = e.response?.data?.message || '状态更新失败'
  } finally {
    busyId.value = null
  }
}

async function remove(segment) {
  if (!window.confirm(`确认删除分群「${segment.display_name || segment.name}」？`)) return
  busyId.value = segment.id
  error.value = ''
  try {
    await api.delete(`/api/segments/${segment.id}`)
    segments.value = segments.value.filter((s) => s.id !== segment.id)
    success.value = `分群「${segment.display_name || segment.name}」已删除`
  } catch (e) {
    error.value = e.response?.data?.message || '删除失败'
  } finally {
    busyId.value = null
  }
}

async function openPreview(segment) {
  previewTarget.value = segment
  previewMembers.value = []
  previewError.value = ''
  previewLoading.value = true
  try {
    const res = await api.get(`/api/segments/${segment.id}/members`, { params: { limit: 100 } })
    previewMembers.value = res.data.members || []
  } catch (e) {
    previewError.value = e.response?.data?.message || '加载成员失败'
  } finally {
    previewLoading.value = false
  }
}

function conditionSummary(segment) {
  try {
    const def = JSON.parse(segment.definition || '{}')
    const n = (def.conditions || []).length
    return `${def.match === 'any' ? '任一' : '全部'}满足 · ${n} 个条件`
  } catch {
    return '—'
  }
}

function fmtTime(v) {
  if (!v) return '未计算'
  return String(v).replace('T', ' ').slice(0, 19)
}

onMounted(load)
watch(currentGameId, load)
</script>

<template>
  <div>
    <div class="sm:flex sm:items-center mb-8 gap-4">
      <div class="sm:flex-auto">
        <h1 class="text-2xl font-bold text-gray-900">用户分群</h1>
        <p class="mt-1 text-sm text-gray-500">按属性 / 行为条件圈选玩家或设备，物化到 ClickHouse 供报表与推送使用</p>
      </div>
      <div class="mt-4 sm:mt-0 sm:ml-16 flex items-center gap-3 flex-wrap">
        <GameSelector />
        <button @click="showCreate = true" class="btn btn-primary">新建分群</button>
        <button @click="load" class="btn btn-secondary" :disabled="loading">刷新</button>
      </div>
    </div>

    <div v-if="success" class="mb-4 rounded-md bg-green-50 p-3 text-sm text-green-700 flex justify-between">
      <span>{{ success }}</span>
      <button @click="success = ''" class="text-green-500 hover:text-green-700">✕</button>
    </div>
    <div v-if="error" class="mb-4 rounded-md bg-red-50 p-3 text-sm text-red-700 flex justify-between">
      <span>{{ error }}</span>
      <button @click="error = ''" class="text-red-500 hover:text-red-700">✕</button>
    </div>

    <div v-if="loading && segments.length === 0" class="text-center py-12">
      <div class="animate-spin rounded-full h-12 w-12 border-b-2 border-primary-600 mx-auto"></div>
      <p class="mt-4 text-gray-500">加载中...</p>
    </div>

    <div v-else-if="segments.length === 0" class="card text-center py-12">
      <p class="text-gray-500">还没有分群</p>
      <p class="text-xs text-gray-400 mt-1">创建一个分群，例如「近 14 天充值 ≥ 2 次的玩家」</p>
    </div>

    <div v-else class="card overflow-x-auto">
      <table class="min-w-full divide-y divide-gray-200">
        <thead>
          <tr class="text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
            <th class="px-4 py-3">名称</th>
            <th class="px-4 py-3">环境</th>
            <th class="px-4 py-3">口径</th>
            <th class="px-4 py-3">条件</th>
            <th class="px-4 py-3">成员数</th>
            <th class="px-4 py-3">最近计算</th>
            <th class="px-4 py-3">状态</th>
            <th class="px-4 py-3 text-right">操作</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-gray-100">
          <tr v-for="s in segments" :key="s.id" class="text-sm text-gray-900">
            <td class="px-4 py-3">
              <div class="font-medium">{{ s.display_name || s.name }}</div>
              <div class="text-xs text-gray-400">{{ s.name }}</div>
            </td>
            <td class="px-4 py-3">{{ s.environment }}</td>
            <td class="px-4 py-3">{{ subjectLabels[s.subject] || s.subject }}</td>
            <td class="px-4 py-3 text-xs text-gray-500">{{ conditionSummary(s) }}</td>
            <td class="px-4 py-3 font-mono">{{ (s.member_count ?? 0).toLocaleString() }}</td>
            <td class="px-4 py-3 text-xs text-gray-500">{{ fmtTime(s.last_computed_at) }}</td>
            <td class="px-4 py-3">
              <span :class="['px-2 py-0.5 rounded-full text-xs font-medium', statusColors[s.status] || statusColors.INACTIVE]">
                {{ s.status === 'ACTIVE' ? '启用' : '停用' }}
              </span>
            </td>
            <td class="px-4 py-3 text-right space-x-1 whitespace-nowrap">
              <button @click="compute(s)" class="text-primary-600 hover:text-primary-800 text-xs font-medium" :disabled="busyId === s.id">
                {{ busyId === s.id ? '计算中…' : '计算' }}
              </button>
              <button @click="openPreview(s)" class="text-gray-600 hover:text-gray-800 text-xs font-medium">成员</button>
              <button @click="toggleStatus(s)" class="text-gray-600 hover:text-gray-800 text-xs font-medium" :disabled="busyId === s.id">
                {{ s.status === 'ACTIVE' ? '停用' : '启用' }}
              </button>
              <button @click="remove(s)" class="text-red-600 hover:text-red-800 text-xs font-medium" :disabled="busyId === s.id">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 新建分群弹层 -->
    <div v-if="showCreate" class="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div class="absolute inset-0 bg-gray-900/50" @click="showCreate = false"></div>
      <div class="relative bg-white rounded-lg shadow-xl max-w-2xl w-full max-h-[90vh] overflow-y-auto p-6">
        <h2 class="text-lg font-bold text-gray-900 mb-4">新建分群</h2>

        <div class="grid grid-cols-2 gap-4 mb-4">
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">标识名 <span class="text-red-500">*</span></label>
            <input v-model="form.name" placeholder="whales" class="input" />
            <p class="mt-1 text-xs text-gray-400">小写字母 / 数字 / 下划线，游戏内唯一</p>
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">显示名</label>
            <input v-model="form.displayName" placeholder="鲸鱼用户" class="input" />
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">环境 <span class="text-red-500">*</span></label>
            <select v-model="form.environment" class="input">
              <option value="dev">dev</option>
              <option value="staging">staging</option>
              <option value="prod">prod</option>
            </select>
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">统计口径</label>
            <select v-model="form.subject" class="input">
              <option value="PLAYER">玩家（player &gt; user &gt; device）</option>
              <option value="DEVICE">设备</option>
            </select>
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">条件组合</label>
            <select v-model="form.match" class="input">
              <option value="all">全部满足（AND）</option>
              <option value="any">任一满足（OR）</option>
            </select>
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">回看窗口（天）</label>
            <input v-model.number="form.withinDays" type="number" min="1" max="365" class="input" />
          </div>
        </div>

        <div class="mb-2 flex items-center justify-between">
          <label class="text-sm font-medium text-gray-700">条件（1~20 个）</label>
          <button
            @click="form.conditions.length < 20 && form.conditions.push(emptyCondition())"
            class="text-xs text-primary-600 hover:text-primary-800 font-medium"
          >+ 添加条件</button>
        </div>
        <div class="space-y-3 mb-4">
          <div v-for="(c, i) in form.conditions" :key="i" class="flex flex-wrap items-center gap-2 rounded-md border border-gray-200 p-3">
            <select v-model="c.kind" class="input !w-auto">
              <option value="attribute">属性条件</option>
              <option value="event">做过事件</option>
              <option value="event_absent">未做事件</option>
            </select>

            <template v-if="c.kind === 'attribute'">
              <select v-model="c.field" class="input !w-auto">
                <option v-for="f in attributeFields" :key="f" :value="f">{{ f }}</option>
              </select>
              <select v-model="c.op" class="input !w-auto">
                <option v-for="o in attributeOps" :key="o" :value="o">{{ o }}</option>
              </select>
              <input v-model="c.value" :placeholder="c.op === 'in' ? 'ios,android（逗号分隔）' : 'ios'" class="input !w-40" />
            </template>

            <template v-else>
              <input v-model="c.eventName" placeholder="事件名，如 purchase" class="input !w-44" />
              <template v-if="c.kind === 'event'">
                <select v-model="c.op" class="input !w-auto">
                  <option v-for="o in eventOps" :key="o.value" :value="o.value">{{ o.label }}</option>
                </select>
                <input v-model.number="c.count" type="number" min="1" class="input !w-24" />
                <input v-model.number="c.withinDays" type="number" min="1" placeholder="窗口天数（可选）" class="input !w-40" />
              </template>
              <template v-else>
                <input v-model.number="c.withinDays" type="number" min="1" placeholder="窗口天数" class="input !w-40" />
              </template>
            </template>

            <button
              @click="form.conditions.splice(i, 1)"
              class="ml-auto text-red-500 hover:text-red-700 text-xs"
              :disabled="form.conditions.length <= 1"
            >移除</button>
          </div>
        </div>

        <div v-if="createError" class="mb-4 rounded-md bg-red-50 p-3 text-sm text-red-700">{{ createError }}</div>

        <div class="flex justify-end gap-3">
          <button @click="showCreate = false" class="btn btn-secondary">取消</button>
          <button @click="create" class="btn btn-primary" :disabled="!canSubmit || creating">
            {{ creating ? '创建中…' : '创建' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 成员预览弹层 -->
    <div v-if="previewTarget" class="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div class="absolute inset-0 bg-gray-900/50" @click="previewTarget = null"></div>
      <div class="relative bg-white rounded-lg shadow-xl max-w-lg w-full p-6">
        <h2 class="text-lg font-bold text-gray-900 mb-1">成员预览</h2>
        <p class="text-sm text-gray-500 mb-4">
          「{{ previewTarget.display_name || previewTarget.name }}」 · 前 {{ previewMembers.length }} 个{{ subjectLabels[previewTarget.subject] || '主体' }} ID
        </p>
        <div v-if="previewLoading" class="text-center py-8 text-gray-500">加载中...</div>
        <div v-else-if="previewError" class="rounded-md bg-red-50 p-3 text-sm text-red-700">{{ previewError }}</div>
        <div v-else-if="previewMembers.length === 0" class="text-center py-8 text-gray-500">
          暂无成员 — 请先点击「计算」物化分群
        </div>
        <ul v-else class="max-h-72 overflow-y-auto divide-y divide-gray-100 font-mono text-sm text-gray-700">
          <li v-for="m in previewMembers" :key="m" class="py-1.5">{{ m }}</li>
        </ul>
        <div class="mt-5 flex justify-end">
          <button @click="previewTarget = null" class="btn btn-secondary">关闭</button>
        </div>
      </div>
    </div>
  </div>
</template>
