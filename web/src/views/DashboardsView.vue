<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import api from '@/services/api'
import GameSelector from '@/components/GameSelector.vue'
import TrendChart from '@/components/TrendChart.vue'
import { useGameList } from '@/composables/useGameList'

const { currentGameId } = useGameList()

// ---- 仪表盘列表与当前选中 ----
const dashboards = ref([])
const activeId = ref(null)
const loading = ref(false)
const error = ref('')
const success = ref('')
const saving = ref(false)

// ---- 本地编辑的 widget 列表 ----
const widgets = ref([])
const dirty = ref(false)

// ---- widget 数据缓存（source+params 去重拉取） ----
const dataCache = ref({})
const dataLoading = ref({})
const dataError = ref({})

// ---- 添加 widget 弹层 ----
const showAdd = ref(false)
const addForm = ref({ type: 'kpi', source: 'online-overview', title: '', span: 6, environment: 'prod', minutes: 10, days: 30, granularity: 'day' })

const SOURCE_LABELS = {
  'online-overview': '实时在线',
  'retention-trend': '留存趋势',
  'payment-funnel': '付费漏斗',
  'crash-trend': 'Crash 趋势'
}
const TYPE_LABELS = { kpi: 'KPI 数字', line: '折线图', bar: '柱状图', table: '表格' }
// 字面量类名白名单：Tailwind 按源码文本扫描生成，动态拼接不会产出样式
const SPAN_CLASSES = {
  3: 'md:col-span-3',
  4: 'md:col-span-4',
  6: 'md:col-span-6',
  8: 'md:col-span-8',
  12: 'md:col-span-12'
}

const SPAN_OPTIONS = [
  { value: 3, label: '1/4 宽' },
  { value: 4, label: '1/3 宽' },
  { value: 6, label: '1/2 宽' },
  { value: 8, label: '2/3 宽' },
  { value: 12, label: '全宽' }
]

const activeDashboard = computed(() => dashboards.value.find((d) => d.id === activeId.value))

function cacheKey(source, params) {
  return source + '|' + JSON.stringify(params || {})
}

function widgetParams(w) {
  const p = {}
  if (w.params?.environment) p.environment = w.params.environment
  if (w.params?.minutes) p.minutes = w.params.minutes
  if (w.params?.days) p.days = w.params.days
  if (w.params?.granularity) p.granularity = w.params.granularity
  return p
}

async function fetchSource(source, params) {
  const g = currentGameId.value
  switch (source) {
    case 'online-overview':
      return (await api.get(`/api/online-metrics/${g}`, { params })).data
    case 'retention-trend':
      return (await api.get(`/api/retention-metrics/${g}/trend`, { params })).data
    case 'payment-funnel':
      return (await api.get(`/api/payment-metrics/${g}/funnel`, { params: { days: params.days || 90 } })).data
    case 'crash-trend':
      return (await api.get(`/api/crash-metrics/${g}/trend`, { params })).data
    default:
      throw new Error(`未知数据源: ${source}`)
  }
}

async function loadWidgetData(w) {
  const key = cacheKey(w.source, w.params)
  if (dataCache.value[key] || dataLoading.value[key]) return
  dataLoading.value[key] = true
  delete dataError.value[key]
  try {
    dataCache.value[key] = await fetchSource(w.source, widgetParams(w))
  } catch (e) {
    dataError.value[key] = e.response?.data?.message || '数据加载失败'
  } finally {
    dataLoading.value[key] = false
  }
}

function loadAllWidgetData() {
  widgets.value.forEach(loadWidgetData)
}

// ---- 各 source × type 的展示模型 ----

function kpiValue(w) {
  const d = dataCache.value[cacheKey(w.source, w.params)]
  if (!d || d.available === false) return null
  switch (w.source) {
    case 'online-overview':
      return { value: (d.online ?? 0).toLocaleString(), unit: '人', sub: `近 ${d.minutes ?? w.params?.minutes ?? 5} 分钟` }
    case 'retention-trend':
      return { value: pct(d.summary?.avgD1Rate), unit: '', sub: `D1 留存 · ${d.summary?.cohorts ?? 0} 个 cohort` }
    case 'payment-funnel':
      return { value: pct(d.funnel?.firstPayRate), unit: '', sub: '注册→首付 转化' }
    case 'crash-trend': {
      const pts = d.points || []
      const last = pts[pts.length - 1]
      return { value: (last?.crashes ?? 0).toLocaleString(), unit: '次', sub: last ? `最新 ${last.date}` : '' }
    }
    default:
      return null
  }
}

function lineModel(w) {
  const d = dataCache.value[cacheKey(w.source, w.params)]
  if (!d) return null
  if (w.source === 'online-overview') {
    const pts = d.trend || []
    return {
      labels: pts.map((p) => String(p.ts).slice(11, 16)),
      series: [{ name: '在线', color: '#3b82f6', values: pts.map((p) => p.online) }]
    }
  }
  if (w.source === 'retention-trend') {
    const pts = (d.points || []).slice(-30)
    return {
      labels: pts.map((p) => String(p.cohort).slice(5)),
      series: [{ name: 'D1 留存', color: '#10b981', values: pts.map((p) => p.d1Rate) }],
      percent: true
    }
  }
  if (w.source === 'crash-trend') {
    const pts = d.points || []
    return {
      labels: pts.map((p) => String(p.date).slice(5)),
      series: [{ name: 'Crash 次数', color: '#ef4444', values: pts.map((p) => p.crashes) }]
    }
  }
  if (w.source === 'payment-funnel') {
    const f = d.funnel || {}
    return {
      labels: ['注册', '首付', '二付'],
      series: [{ name: '人数', color: '#8b5cf6', values: [f.registered, f.firstPay, f.secondPay] }]
    }
  }
  return null
}

function barModel(w) {
  const d = dataCache.value[cacheKey(w.source, w.params)]
  if (!d) return null
  if (w.source === 'online-overview') {
    return (d.byPlatform || []).map((r) => ({ label: r.key, value: r.online }))
  }
  if (w.source === 'retention-trend') {
    return (d.points || []).slice(-14).map((p) => ({ label: String(p.cohort).slice(5), value: p.newUsers }))
  }
  if (w.source === 'payment-funnel') {
    const f = d.funnel || {}
    return [
      { label: '注册', value: f.registered },
      { label: '首付', value: f.firstPay },
      { label: '二付', value: f.secondPay },
      { label: '留存30', value: f.retained30 }
    ]
  }
  if (w.source === 'crash-trend') {
    return (d.points || []).slice(-14).map((p) => ({ label: String(p.date).slice(5), value: p.affectedDevices }))
  }
  return null
}

const STEP_NAMES = { registered: '注册', firstPay: '首付', secondPay: '二付', retained30: '留存30' }

function tableModel(w) {
  const d = dataCache.value[cacheKey(w.source, w.params)]
  if (!d) return null
  if (w.source === 'online-overview') {
    const rows = [
      ...(d.byPlatform || []).map((r) => ({ dim: '平台', key: r.key, value: r.online })),
      ...(d.byAppVersion || []).map((r) => ({ dim: '版本', key: r.key, value: r.online }))
    ]
    return { head: ['维度', '取值', '在线'], rows }
  }
  if (w.source === 'retention-trend') {
    const rows = (d.points || []).slice(-14).map((p) => ({
      dim: p.cohort, key: `${(p.newUsers ?? 0).toLocaleString()} 新增`, value: pct(p.d1Rate)
    }))
    return { head: ['Cohort', '规模', 'D1 留存'], rows }
  }
  if (w.source === 'payment-funnel') {
    const f = d.funnel || {}
    const rows = Object.keys(STEP_NAMES).map((k) => ({
      dim: STEP_NAMES[k], key: (f[k] ?? 0).toLocaleString(), value: k === 'registered' ? '—' : pct(rateOf(f, k))
    }))
    return { head: ['漏斗步', '人数', '转化率'], rows }
  }
  if (w.source === 'crash-trend') {
    const rows = (d.points || []).slice(-14).map((p) => ({
      dim: p.date, key: `${(p.crashes ?? 0).toLocaleString()} 次`, value: `${(p.affectedDevices ?? 0).toLocaleString()} 设备`
    }))
    return { head: ['日期', 'Crash', '影响设备'], rows }
  }
  return null
}

function rateOf(f, k) {
  if (k === 'firstPay') return f.firstPayRate
  if (k === 'secondPay') return f.secondPayRate
  if (k === 'retained30') return f.retained30Rate
  return null
}

function pct(v) {
  return v == null ? '—' : `${(v * 100).toFixed(1)}%`
}

function barMax(model) {
  return Math.max(1, ...(model || []).map((r) => r.value || 0))
}

function widgetTitle(w) {
  return w.title || SOURCE_LABELS[w.source] || w.source
}

// ---- 仪表盘 CRUD ----

async function load() {
  if (!currentGameId.value) return
  loading.value = true
  error.value = ''
  try {
    const res = await api.get(`/api/games/${currentGameId.value}/dashboards`)
    dashboards.value = res.data
    if (!dashboards.value.find((d) => d.id === activeId.value)) {
      activeId.value = dashboards.value[0]?.id || null
    }
    applyActive()
  } catch (e) {
    error.value = e.response?.data?.message || '加载仪表盘列表失败'
  } finally {
    loading.value = false
  }
}

function applyActive() {
  const d = activeDashboard.value
  widgets.value = d ? parseLayout(d.layout) : []
  dirty.value = false
  dataCache.value = {}
  dataError.value = {}
  loadAllWidgetData()
}

function parseLayout(layout) {
  try {
    const def = JSON.parse(layout || '{}')
    return (def.widgets || []).map((w, i) => ({ ...w, id: w.id || `w${Date.now()}_${i}` }))
  } catch {
    return []
  }
}

async function createDashboard() {
  const name = window.prompt('新仪表盘名称（游戏内唯一）')
  if (!name) return
  error.value = ''
  try {
    // 后端要求至少一个 widget：创建即带默认在线 KPI，后续可自由增删
    const res = await api.post(`/api/games/${currentGameId.value}/dashboards`, {
      name,
      layout: JSON.stringify({
        widgets: [{ id: `w${Date.now()}`, type: 'kpi', source: 'online-overview', title: '当前在线', span: 3, params: { minutes: 10 } }]
      })
    })
    dashboards.value.push(res.data)
    activeId.value = res.data.id
    applyActive()
  } catch (e) {
    error.value = e.response?.data?.message || '创建失败'
  }
}

async function removeDashboard() {
  const d = activeDashboard.value
  if (!d || !window.confirm(`确认删除仪表盘「${d.name}」？`)) return
  try {
    await api.delete(`/api/dashboards/${d.id}`)
    dashboards.value = dashboards.value.filter((x) => x.id !== d.id)
    activeId.value = dashboards.value[0]?.id || null
    applyActive()
    success.value = `仪表盘「${d.name}」已删除`
  } catch (e) {
    error.value = e.response?.data?.message || '删除失败'
  }
}

function addWidget() {
  const f = addForm.value
  const params = {}
  if (['online-overview', 'retention-trend', 'payment-funnel', 'crash-trend'].includes(f.source)) {
    params.environment = f.environment
  }
  if (f.source === 'online-overview') params.minutes = Number(f.minutes)
  if (['retention-trend', 'payment-funnel', 'crash-trend'].includes(f.source)) params.days = Number(f.days)
  if (f.source === 'retention-trend') params.granularity = f.granularity

  widgets.value.push({
    id: `w${Date.now()}`,
    type: f.type,
    source: f.source,
    title: f.title || '',
    span: f.span,
    params
  })
  dirty.value = true
  showAdd.value = false
  loadAllWidgetData()
}

function removeWidget(i) {
  widgets.value.splice(i, 1)
  dirty.value = true
}

async function save() {
  const d = activeDashboard.value
  if (!d) return
  saving.value = true
  error.value = ''
  success.value = ''
  try {
    const layout = JSON.stringify({
      widgets: widgets.value.map(({ id, type, source, title, span, params }) => ({ id, type, source, title, span, params }))
    })
    const res = await api.put(`/api/dashboards/${d.id}`, { layout })
    d.layout = res.data.layout
    dirty.value = false
    success.value = '布局已保存'
  } catch (e) {
    error.value = e.response?.data?.message || '保存失败'
  } finally {
    saving.value = false
  }
}

onMounted(load)
watch(currentGameId, load)
watch(activeId, applyActive)
</script>

<template>
  <div>
    <div class="sm:flex sm:items-center mb-8 gap-4">
      <div class="sm:flex-auto">
        <h1 class="text-2xl font-bold text-gray-900">自定义仪表盘</h1>
        <p class="mt-1 text-sm text-gray-500">KPI / 折线 / 柱状 / 表格 widget 自由组合，按游戏保存布局</p>
      </div>
      <div class="mt-4 sm:mt-0 sm:ml-16 flex items-center gap-3 flex-wrap">
        <GameSelector />
        <select v-if="dashboards.length" v-model="activeId" class="input !w-auto">
          <option v-for="d in dashboards" :key="d.id" :value="d.id">{{ d.name }}</option>
        </select>
        <button @click="createDashboard" class="btn btn-secondary">新建</button>
        <button v-if="activeDashboard" @click="removeDashboard" class="btn btn-secondary !text-red-600">删除</button>
        <button @click="showAdd = true" class="btn btn-secondary" :disabled="!activeDashboard">+ Widget</button>
        <button @click="save" class="btn btn-primary" :disabled="!activeDashboard || !dirty || saving">
          {{ saving ? '保存中…' : dirty ? '保存布局' : '已保存' }}
        </button>
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

    <div v-if="loading && dashboards.length === 0" class="text-center py-12">
      <div class="animate-spin rounded-full h-12 w-12 border-b-2 border-primary-600 mx-auto"></div>
      <p class="mt-4 text-gray-500">加载中...</p>
    </div>

    <div v-else-if="dashboards.length === 0" class="card text-center py-12">
      <p class="text-gray-500">还没有自定义仪表盘</p>
      <p class="text-xs text-gray-400 mt-1">点击「新建」创建一个，然后用「+ Widget」自由组合报表卡片</p>
    </div>

    <div v-else-if="widgets.length === 0" class="card text-center py-12">
      <p class="text-gray-500">空仪表盘</p>
      <p class="text-xs text-gray-400 mt-1">点击「+ Widget」添加第一张卡片</p>
    </div>

    <!-- 12 栅格 widget 布局 -->
    <div v-else class="grid grid-cols-12 gap-5">
      <div
        v-for="(w, i) in widgets"
        :key="w.id"
        class="card relative col-span-12"
        :class="SPAN_CLASSES[w.span] || SPAN_CLASSES[6]"
      >
        <div class="flex items-center justify-between mb-3">
          <h3 class="text-sm font-medium text-gray-900 truncate">{{ widgetTitle(w) }}</h3>
          <div class="flex items-center gap-2">
            <span class="text-[10px] px-1.5 py-0.5 rounded bg-gray-100 text-gray-500">{{ TYPE_LABELS[w.type] }}</span>
            <button @click="removeWidget(i)" class="text-gray-300 hover:text-red-500 text-xs" title="移除">✕</button>
          </div>
        </div>

        <div v-if="dataLoading[cacheKey(w.source, w.params)]" class="py-8 text-center text-sm text-gray-400">加载中...</div>
        <div v-else-if="dataError[cacheKey(w.source, w.params)]" class="py-6 text-center text-xs text-red-500">
          {{ dataError[cacheKey(w.source, w.params)] }}
        </div>
        <div v-else-if="dataCache[cacheKey(w.source, w.params)]?.available === false" class="py-6 text-center text-xs text-gray-400">
          ClickHouse 未配置，数据不可用
        </div>

        <!-- KPI -->
        <div v-else-if="w.type === 'kpi'" class="py-2">
          <p class="text-4xl font-bold text-gray-900">
            {{ kpiValue(w)?.value ?? '—' }}<span class="text-base font-normal text-gray-400 ml-1">{{ kpiValue(w)?.unit }}</span>
          </p>
          <p class="mt-1 text-xs text-gray-400">{{ kpiValue(w)?.sub }}</p>
        </div>

        <!-- 折线 -->
        <TrendChart
          v-else-if="w.type === 'line' && lineModel(w)"
          :labels="lineModel(w).labels"
          :series="lineModel(w).series"
          :percent="!!lineModel(w).percent"
          :height="240"
        />

        <!-- 柱状 -->
        <div v-else-if="w.type === 'bar' && barModel(w)" class="space-y-2.5 py-1">
          <div v-for="row in barModel(w)" :key="row.label" class="flex items-center gap-2 text-xs">
            <span class="w-16 text-right text-gray-500 truncate">{{ row.label }}</span>
            <div class="flex-1 h-4 bg-gray-100 rounded overflow-hidden">
              <div class="h-full bg-primary-500 rounded" :style="{ width: ((row.value || 0) / barMax(barModel(w))) * 100 + '%' }"></div>
            </div>
            <span class="w-16 font-mono text-gray-700">{{ (row.value ?? 0).toLocaleString() }}</span>
          </div>
        </div>

        <!-- 表格 -->
        <div v-else-if="w.type === 'table' && tableModel(w)" class="overflow-x-auto">
          <table class="min-w-full text-xs">
            <thead>
              <tr class="text-left text-gray-400 border-b border-gray-100">
                <th v-for="h in tableModel(w).head" :key="h" class="py-1.5 pr-4 font-medium">{{ h }}</th>
              </tr>
            </thead>
            <tbody class="divide-y divide-gray-50">
              <tr v-for="(row, ri) in tableModel(w).rows" :key="ri">
                <td class="py-1.5 pr-4 text-gray-500">{{ row.dim }}</td>
                <td class="py-1.5 pr-4 text-gray-900">{{ row.key }}</td>
                <td class="py-1.5 pr-4 font-mono text-gray-700">{{ row.value }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div v-else class="py-6 text-center text-xs text-gray-400">暂无数据</div>
      </div>
    </div>

    <!-- 添加 widget 弹层 -->
    <div v-if="showAdd" class="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div class="absolute inset-0 bg-gray-900/50" @click="showAdd = false"></div>
      <div class="relative bg-white rounded-lg shadow-xl max-w-md w-full p-6">
        <h2 class="text-lg font-bold text-gray-900 mb-4">添加 Widget</h2>

        <div class="space-y-4">
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">数据源</label>
            <select v-model="addForm.source" class="input">
              <option v-for="(label, key) in SOURCE_LABELS" :key="key" :value="key">{{ label }}</option>
            </select>
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">展示类型</label>
            <select v-model="addForm.type" class="input">
              <option v-for="(label, key) in TYPE_LABELS" :key="key" :value="key">{{ label }}</option>
            </select>
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">标题（可选）</label>
            <input v-model="addForm.title" placeholder="缺省用数据源名" class="input" />
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">宽度</label>
            <select v-model.number="addForm.span" class="input">
              <option v-for="o in SPAN_OPTIONS" :key="o.value" :value="o.value">{{ o.label }}</option>
            </select>
          </div>
          <div class="grid grid-cols-2 gap-3">
            <div>
              <label class="block text-sm font-medium text-gray-700 mb-1">环境</label>
              <select v-model="addForm.environment" class="input">
                <option value="dev">dev</option>
                <option value="staging">staging</option>
                <option value="prod">prod</option>
              </select>
            </div>
            <div v-if="addForm.source === 'online-overview'">
              <label class="block text-sm font-medium text-gray-700 mb-1">近 N 分钟</label>
              <input v-model.number="addForm.minutes" type="number" min="1" max="60" class="input" />
            </div>
            <div v-if="addForm.source !== 'online-overview'">
              <label class="block text-sm font-medium text-gray-700 mb-1">近 N 天</label>
              <input v-model.number="addForm.days" type="number" min="1" max="365" class="input" />
            </div>
          </div>
        </div>

        <div class="mt-6 flex justify-end gap-3">
          <button @click="showAdd = false" class="btn btn-secondary">取消</button>
          <button @click="addWidget" class="btn btn-primary">添加</button>
        </div>
      </div>
    </div>
  </div>
</template>
