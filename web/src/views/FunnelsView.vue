<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import api from '@/services/api'
import GameSelector from '@/components/GameSelector.vue'
import { useGameList } from '@/composables/useGameList'

const { currentGameId } = useGameList()

const funnels = ref([])
const total = ref(0)
const loading = ref(false)
const error = ref('')

// 编辑表单
const showForm = ref(false)
const editingId = ref(null)
const form = emptyForm()
const stepTypes = ['STANDARD', 'SEQUENTIAL', 'TIME_WINDOW', 'UNORDERED']
const typeLabels = { STANDARD: '标准（任意顺序）', SEQUENTIAL: '顺序', TIME_WINDOW: '时间窗口', UNORDERED: '无序' }

// 结果
const days = ref(30)
const dayOptions = [7, 30, 90, 180, 365]
const selected = ref(null)
const result = ref(null)
const resultLoading = ref(false)
const resultError = ref('')

function emptyForm() {
  return {
    name: '',
    description: '',
    type: 'SEQUENTIAL',
    userKey: 'user_id',
    timeWindowSec: 86400,
    steps: [emptyStep()]
  }
}

function emptyStep() {
  return { name: '', eventName: '', timeWindowSec: 3600, optional: false }
}

async function loadFunnels() {
  if (!currentGameId.value) return
  loading.value = true
  error.value = ''
  try {
    const response = await api.get('/api/funnels', {
      params: { gameId: currentGameId.value, page: 0, size: 50 }
    })
    funnels.value = response.data.content || []
    total.value = response.data.totalElements || 0
  } catch (e) {
    error.value = e.response?.data?.message || '加载漏斗列表失败'
    console.error('Failed to load funnels:', e)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  Object.assign(form, emptyForm(), { steps: [emptyStep()] })
  showForm.value = true
}

function openEdit(f) {
  editingId.value = f.id
  Object.assign(form, {
    name: f.name,
    description: f.description || '',
    type: f.type || 'SEQUENTIAL',
    userKey: f.userKey || 'user_id',
    timeWindowSec: f.timeWindowSec ?? 86400,
    steps: (f.steps || []).map(s => ({
      id: s.id,
      name: s.name,
      eventName: s.eventName,
      timeWindowSec: s.timeWindowSec ?? 0,
      optional: !!s.optional
    }))
  })
  if (form.steps.length === 0) form.steps = [emptyStep()]
  showForm.value = true
}

async function saveFunnel() {
  if (!form.name || form.steps.some(s => !s.name || !s.eventName)) {
    error.value = '漏斗名称与每步的名称/事件名均为必填'
    return
  }
  error.value = ''
  try {
    const payload = {
      gameId: currentGameId.value,
      name: form.name,
      description: form.description || null,
      type: form.type,
      userKey: form.userKey,
      timeWindowSec: Number(form.timeWindowSec) || null,
      enabled: true,
      steps: form.steps.map((s, i) => ({
        ...(s.id ? { id: s.id } : {}),
        stepOrder: i + 1,
        name: s.name,
        eventName: s.eventName,
        timeWindowSec: Number(s.timeWindowSec) || null,
        optional: !!s.optional
      }))
    }
    if (editingId.value) {
      await api.put(`/api/funnels/${editingId.value}`, payload)
    } else {
      await api.post('/api/funnels', payload)
    }
    showForm.value = false
    await loadFunnels()
  } catch (e) {
    error.value = e.response?.data?.message || '保存漏斗失败'
    console.error('Failed to save funnel:', e)
  }
}

async function toggleFunnel(f) {
  try {
    await api.post(`/api/funnels/${f.id}/toggle`, { enabled: !f.enabled })
    await loadFunnels()
  } catch (e) {
    error.value = e.response?.data?.message || '切换启停失败'
  }
}

async function deleteFunnel(f) {
  if (!confirm(`确认删除漏斗「${f.name}」？删除后 Flink job 重启前仍按旧配置运行。`)) return
  try {
    await api.delete(`/api/funnels/${f.id}`)
    if (selected.value?.id === f.id) {
      selected.value = null
      result.value = null
    }
    await loadFunnels()
  } catch (e) {
    error.value = e.response?.data?.message || '删除漏斗失败'
  }
}

async function loadResult(f) {
  selected.value = f
  resultLoading.value = true
  resultError.value = ''
  result.value = null
  try {
    const response = await api.get(`/api/funnels/${f.id}/results`, {
      params: { gameId: currentGameId.value, days: days.value }
    })
    result.value = response.data
  } catch (e) {
    resultError.value = e.response?.data?.message || '加载漏斗结果失败'
    console.error('Failed to load funnel results:', e)
  } finally {
    resultLoading.value = false
  }
}

// 条形图：第一步 users 为 100% 基准
const resultBars = computed(() => {
  const steps = result.value?.steps || []
  const base = steps.length > 0 ? steps[0].users : 0
  return steps.map(s => ({
    ...s,
    widthPct: base > 0 ? (s.users / base) * 100 : 0
  }))
})

function pct(v) {
  return v == null ? '-' : `${(v * 100).toFixed(2)}%`
}

onMounted(loadFunnels)
watch(currentGameId, () => {
  selected.value = null
  result.value = null
  loadFunnels()
})
watch(days, () => { if (selected.value) loadResult(selected.value) })
</script>

<template>
  <div>
    <div class="sm:flex sm:items-center mb-8 gap-4">
      <div class="sm:flex-auto">
        <h1 class="text-2xl font-bold text-gray-900">漏斗分析</h1>
        <p class="mt-1 text-sm text-gray-500">
          自定义多步转化漏斗；配置由 Flink funnels job 加载，改动需重启 job 生效
        </p>
      </div>
      <div class="mt-4 sm:mt-0 sm:ml-16 flex items-center gap-3 flex-wrap">
        <GameSelector />
        <button @click="openCreate" class="btn btn-primary">新建漏斗</button>
        <button @click="loadFunnels" class="btn btn-secondary">刷新</button>
      </div>
    </div>

    <div v-if="error" class="card mb-6 text-red-600 text-sm">{{ error }}</div>

    <!-- 创建/编辑表单 -->
    <div v-if="showForm" class="card mb-8">
      <h3 class="text-lg font-medium text-gray-900 mb-6">
        {{ editingId ? '编辑漏斗' : '新建漏斗' }}
      </h3>
      <div class="grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-4 mb-6">
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">名称</label>
          <input v-model="form.name" class="input" placeholder="如：注册→付费转化" />
        </div>
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">类型</label>
          <select v-model="form.type" class="input">
            <option v-for="t in stepTypes" :key="t" :value="t">{{ typeLabels[t] }}</option>
          </select>
        </div>
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">用户标识字段</label>
          <select v-model="form.userKey" class="input">
            <option value="user_id">user_id</option>
            <option value="device_id">device_id</option>
            <option value="player_id">player_id</option>
          </select>
        </div>
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">总时间窗（秒）</label>
          <input v-model.number="form.timeWindowSec" type="number" min="0" class="input" placeholder="86400" />
        </div>
        <div class="sm:col-span-2 lg:col-span-4">
          <label class="block text-sm font-medium text-gray-700 mb-1">描述</label>
          <input v-model="form.description" class="input" placeholder="选填" />
        </div>
      </div>

      <h4 class="text-sm font-medium text-gray-700 mb-3">步骤（按顺序）</h4>
      <div v-for="(s, i) in form.steps" :key="i" class="grid grid-cols-1 gap-3 sm:grid-cols-12 items-end mb-3">
        <div class="sm:col-span-1">
          <label class="block text-sm font-medium text-gray-700 mb-1">#</label>
          <div class="input bg-gray-50 text-center">{{ i + 1 }}</div>
        </div>
        <div class="sm:col-span-3">
          <label class="block text-sm font-medium text-gray-700 mb-1">步骤名</label>
          <input v-model="s.name" class="input" placeholder="如：安装" />
        </div>
        <div class="sm:col-span-3">
          <label class="block text-sm font-medium text-gray-700 mb-1">事件名</label>
          <input v-model="s.eventName" class="input" placeholder="如：level_start" />
        </div>
        <div class="sm:col-span-2">
          <label class="block text-sm font-medium text-gray-700 mb-1">距上步窗口（秒）</label>
          <input v-model.number="s.timeWindowSec" type="number" min="0" class="input" placeholder="3600" />
        </div>
        <div class="sm:col-span-2">
          <label class="flex items-center gap-2 text-sm text-gray-700 mb-1 h-7">
            <input v-model="s.optional" type="checkbox" class="h-4 w-4" />
            可选步骤
          </label>
        </div>
        <div class="sm:col-span-1">
          <button
            @click="form.steps.splice(i, 1)"
            :disabled="form.steps.length <= 1"
            class="btn btn-danger !px-3 w-full"
            :title="form.steps.length <= 1 ? '至少保留一步' : '删除该步骤'"
          >×</button>
        </div>
      </div>
      <button @click="form.steps.push(emptyStep())" class="btn btn-secondary mb-6">+ 添加步骤</button>

      <div class="flex gap-3">
        <button @click="saveFunnel" class="btn btn-primary">保存</button>
        <button @click="showForm = false" class="btn btn-secondary">取消</button>
      </div>
      <p class="text-xs text-gray-400 mt-4">
        保存后需重启 configurable-funnels-job 加载新配置；job 按日累计各步用户数到 ClickHouse。
      </p>
    </div>

    <!-- 漏斗列表 -->
    <div class="card overflow-x-auto">
      <h3 class="text-lg font-medium text-gray-900 mb-4">漏斗列表（{{ total }}）</h3>
      <div v-if="loading" class="text-center py-8">
        <div class="animate-spin rounded-full h-10 w-10 border-b-2 border-primary-600 mx-auto"></div>
      </div>
      <table class="table" v-else-if="funnels.length > 0">
        <thead>
          <tr>
            <th>名称</th>
            <th>类型</th>
            <th>步骤数</th>
            <th>总窗口</th>
            <th>状态</th>
            <th class="text-right">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="f in funnels" :key="f.id">
            <td class="font-medium">
              {{ f.name }}
              <span v-if="selected?.id === f.id" class="badge badge-info ml-2">当前查看</span>
            </td>
            <td>{{ typeLabels[f.type] || f.type }}</td>
            <td>{{ (f.steps || []).length }}</td>
            <td>{{ f.timeWindowSec ? `${Math.round(f.timeWindowSec / 3600)} 小时` : '-' }}</td>
            <td>
              <span :class="f.enabled ? 'badge badge-success' : 'badge badge-warning'">
                {{ f.enabled ? '启用' : '停用' }}
              </span>
            </td>
            <td class="text-right space-x-2 whitespace-nowrap">
              <button @click="loadResult(f)" class="btn btn-secondary !px-2 !py-1 text-xs">结果</button>
              <button @click="openEdit(f)" class="btn btn-secondary !px-2 !py-1 text-xs">编辑</button>
              <button @click="toggleFunnel(f)" class="btn btn-secondary !px-2 !py-1 text-xs">
                {{ f.enabled ? '停用' : '启用' }}
              </button>
              <button @click="deleteFunnel(f)" class="btn btn-danger !px-2 !py-1 text-xs">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else class="text-center py-8 text-gray-500">暂无漏斗，点击右上角「新建漏斗」创建</p>
    </div>

    <!-- 结果 -->
    <div v-if="selected" class="mt-8">
      <div class="sm:flex sm:items-center mb-4 gap-3">
        <h2 class="text-lg font-bold text-gray-900">结果：{{ selected.name }}</h2>
        <select v-model="days" class="input !w-auto !py-1 text-sm">
          <option v-for="d in dayOptions" :key="d" :value="d">近 {{ d }} 天</option>
        </select>
      </div>

      <div v-if="resultLoading" class="card text-center py-8">
        <div class="animate-spin rounded-full h-10 w-10 border-b-2 border-primary-600 mx-auto"></div>
      </div>
      <div v-else-if="resultError" class="card text-center py-8 text-red-600">{{ resultError }}</div>
      <div v-else-if="result && result.available === false" class="card text-center py-8">
        <p class="text-gray-500">ClickHouse 未配置，漏斗结果不可用</p>
        <p class="text-xs text-gray-400 mt-1">部署时设置 CLICKHOUSE_URL 后自动启用</p>
      </div>
      <template v-else-if="result">
        <div class="grid grid-cols-1 gap-5 sm:grid-cols-3 mb-6">
          <div class="card">
            <p class="text-sm font-medium text-gray-500">进入用户（第 1 步）</p>
            <p class="text-2xl font-semibold text-gray-900 mt-1">
              {{ (result.overall?.firstUsers ?? 0).toLocaleString() }}
            </p>
          </div>
          <div class="card">
            <p class="text-sm font-medium text-gray-500">完成末步用户</p>
            <p class="text-2xl font-semibold text-indigo-600 mt-1">
              {{ (result.overall?.lastUsers ?? 0).toLocaleString() }}
            </p>
          </div>
          <div class="card">
            <p class="text-sm font-medium text-gray-500">整体转化率</p>
            <p class="text-2xl font-semibold text-emerald-600 mt-1">{{ pct(result.overall?.rate) }}</p>
          </div>
        </div>

        <div class="card mb-6" v-if="resultBars.length > 0">
          <h3 class="text-base font-medium text-gray-900 mb-6">逐步转化（以第 1 步为 100% 基准）</h3>
          <div class="space-y-5">
            <div v-for="s in resultBars" :key="s.step">
              <div class="flex items-center justify-between mb-1">
                <span class="text-sm font-medium text-gray-700">#{{ s.step }} {{ s.stepName }}</span>
                <span class="text-sm text-gray-500">
                  {{ s.users.toLocaleString() }} 人
                  <span v-if="s.stepRate != null" class="ml-2 text-gray-400">上步转化 {{ pct(s.stepRate) }}</span>
                </span>
              </div>
              <div class="w-full bg-gray-100 rounded-full h-8 overflow-hidden">
                <div
                  class="h-8 rounded-full bg-primary-500 transition-all duration-500 flex items-center justify-end pr-3"
                  :style="{ width: Math.max(s.widthPct, s.users > 0 ? 2 : 0) + '%' }"
                >
                  <span class="text-xs font-medium text-white">{{ s.widthPct.toFixed(1) }}%</span>
                </div>
              </div>
            </div>
          </div>
        </div>

        <div class="card overflow-x-auto">
          <h3 class="text-base font-medium text-gray-900 mb-4">明细</h3>
          <table class="table" v-if="resultBars.length > 0">
            <thead>
              <tr>
                <th>步骤</th>
                <th>名称</th>
                <th>用户数</th>
                <th>总体转化</th>
                <th>上步转化</th>
                <th>较上步流失</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="s in resultBars" :key="s.step">
                <td class="font-medium">#{{ s.step }}</td>
                <td>{{ s.stepName }}</td>
                <td>{{ s.users.toLocaleString() }}</td>
                <td>{{ pct(s.overallRate) }}</td>
                <td>{{ pct(s.stepRate) }}</td>
                <td>{{ s.step === 1 ? '-' : s.dropOff.toLocaleString() }}</td>
              </tr>
            </tbody>
          </table>
          <p v-else class="text-center py-6 text-gray-500">
            窗口内无数据——确认 job 已重启加载该配置且有事件流入
          </p>
        </div>
        <p class="text-xs text-gray-400 mt-3">
          口径说明：users 为按日去重用户数的跨日累加（同一用户跨天重复计入）；行级数据由 Flink job 按日落库。
        </p>
      </template>
    </div>
  </div>
</template>
