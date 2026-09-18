<script setup>
import { ref, reactive, watch, onMounted } from 'vue'
import api from '@/services/api'
import GameSelector from '@/components/GameSelector.vue'
import { useGameList } from '@/composables/useGameList'

const { currentGameId } = useGameList()

const rules = ref([])
const alerts = ref([])
const loading = ref(false)
const error = ref('')
const showForm = ref(false)
const saving = ref(false)
const evalResult = ref(null)
const actionTarget = ref(null)   // { alert, action: 'acknowledge' | 'resolve' }
const actionComment = ref('')

const metricOptions = [
  { value: 'DAU', label: 'DAU（活跃主体数）' },
  { value: 'REVENUE', label: '收入（正收入合计）' },
  { value: 'CRASH_RATE', label: '崩溃率（error 主体占比）' }
]
const comparisonLabels = { GT: '高于', LT: '低于', BOTH: '双向 ±' }
const windowLabels = { TODAY: '今日至今', HOUR_1: '近 1 小时' }
const severityColors = {
  INFO: 'bg-gray-100 text-gray-600',
  WARNING: 'bg-yellow-100 text-yellow-700',
  ERROR: 'bg-orange-100 text-orange-700',
  CRITICAL: 'bg-red-100 text-red-700',
  EMERGENCY: 'bg-red-600 text-white'
}
const statusColors = {
  OPEN: 'bg-red-100 text-red-700',
  ACKNOWLEDGED: 'bg-blue-100 text-blue-700',
  INVESTIGATING: 'bg-indigo-100 text-indigo-700',
  RESOLVED: 'bg-green-100 text-green-700',
  CLOSED: 'bg-gray-100 text-gray-500',
  SNOOZED: 'bg-gray-100 text-gray-500'
}

const form = reactive({
  name: '',
  metricType: 'DAU',
  conditionType: 'BASELINE_DEVIATION',
  comparison: 'LT',
  threshold: null,
  deviationPct: 30,
  environment: '',
  window: 'TODAY',
  severity: 'WARNING',
  enabled: true,
  notifyWebhook: true
})

function resetForm() {
  Object.assign(form, {
    name: '',
    metricType: 'DAU',
    conditionType: 'BASELINE_DEVIATION',
    comparison: 'LT',
    threshold: null,
    deviationPct: 30,
    environment: '',
    window: 'TODAY',
    severity: 'WARNING',
    enabled: true,
    notifyWebhook: true
  })
}

function conditionText(rule) {
  if (rule.conditionType === 'ABSOLUTE') {
    return `${comparisonLabels[rule.comparison] || rule.comparison} ${rule.threshold}`
  }
  const dir = rule.comparison === 'LT' ? '下跌' : rule.comparison === 'GT' ? '上涨' : '双向'
  return `较昨日同时段${dir}超 ${rule.deviationPct}%`
}

async function load() {
  if (!currentGameId.value) return
  loading.value = true
  error.value = ''
  evalResult.value = null
  try {
    const [ruleRes, alertRes] = await Promise.all([
      api.get(`/api/games/${currentGameId.value}/alert-rules`),
      api.get(`/api/games/${currentGameId.value}/alerts`, { params: { limit: 50 } })
    ])
    rules.value = ruleRes.data
    alerts.value = alertRes.data
  } catch (e) {
    error.value = e.response?.data?.message || '加载业务告警失败'
    console.error('Failed to load alerts:', e)
  } finally {
    loading.value = false
  }
}

async function saveRule() {
  if (!form.name.trim()) {
    error.value = '请填写规则名称'
    return
  }
  saving.value = true
  error.value = ''
  try {
    await api.post(`/api/games/${currentGameId.value}/alert-rules`, {
      name: form.name.trim(),
      metricType: form.metricType,
      conditionType: form.conditionType,
      comparison: form.comparison,
      threshold: form.conditionType === 'ABSOLUTE' ? Number(form.threshold) : null,
      deviationPct: form.conditionType === 'BASELINE_DEVIATION' ? Number(form.deviationPct) : null,
      environment: form.environment.trim() || null,
      window: form.window,
      severity: form.severity,
      enabled: form.enabled,
      notifyWebhook: form.notifyWebhook
    })
    showForm.value = false
    resetForm()
    await load()
  } catch (e) {
    error.value = e.response?.data?.message || '创建规则失败'
  } finally {
    saving.value = false
  }
}

async function toggleRule(rule) {
  try {
    await api.put(`/api/games/${currentGameId.value}/alert-rules/${rule.id}`, { ...rule, enabled: !rule.enabled })
    await load()
  } catch (e) {
    error.value = e.response?.data?.message || '更新规则失败'
  }
}

async function deleteRule(rule) {
  if (!confirm(`确认删除规则「${rule.name}」？`)) return
  try {
    await api.delete(`/api/games/${currentGameId.value}/alert-rules/${rule.id}`)
    await load()
  } catch (e) {
    error.value = e.response?.data?.message || '删除规则失败'
  }
}

async function evaluateRule(rule) {
  evalResult.value = null
  error.value = ''
  try {
    const res = await api.post(`/api/games/${currentGameId.value}/alert-rules/${rule.id}/evaluate`)
    evalResult.value = { rule, ...res.data }
  } catch (e) {
    error.value = e.response?.data?.message || '试算失败'
  }
}

function openAction(alert, action) {
  actionTarget.value = { alert, action }
  actionComment.value = ''
}

async function submitAction() {
  const { alert, action } = actionTarget.value
  try {
    await api.post(
      `/api/games/${currentGameId.value}/alerts/${alert.id}/${action}`,
      { comment: actionComment.value || null }
    )
    actionTarget.value = null
    await load()
  } catch (e) {
    error.value = e.response?.data?.message || '操作失败'
    actionTarget.value = null
  }
}

function fmtTime(ts) {
  return ts ? String(ts).replace('T', ' ').slice(0, 19) : '-'
}

function fmtValue(v) {
  return v == null ? '-' : Number(v).toLocaleString(undefined, { maximumFractionDigits: 2 })
}

onMounted(load)
watch(currentGameId, load)
</script>

<template>
  <div>
    <div class="sm:flex sm:items-center mb-8 gap-4">
      <div class="sm:flex-auto">
        <h1 class="text-2xl font-bold text-gray-900">业务告警</h1>
        <p class="mt-1 text-sm text-gray-500">DAU / 收入 / 崩溃率的阈值与同比偏差告警：规则配置、定时评估与告警处理</p>
      </div>
      <div class="mt-4 sm:mt-0 sm:ml-16 flex items-center gap-3 flex-wrap">
        <GameSelector />
        <button @click="load" class="btn btn-secondary" :disabled="loading">刷新</button>
        <button @click="showForm = !showForm" class="btn btn-primary">
          {{ showForm ? '收起表单' : '新建规则' }}
        </button>
      </div>
    </div>

    <div v-if="error" class="card mb-6 text-sm text-red-600">{{ error }}</div>

    <!-- 新建规则表单 -->
    <div v-if="showForm" class="card mb-8">
      <h3 class="text-base font-medium text-gray-900 mb-4">新建告警规则</h3>
      <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        <div class="md:col-span-2 lg:col-span-3">
          <label class="block text-sm font-medium text-gray-700 mb-1">规则名称</label>
          <input v-model="form.name" class="input" placeholder="如：今日收入下限 / DAU 骤降" />
        </div>
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">指标</label>
          <select v-model="form.metricType" class="input">
            <option v-for="m in metricOptions" :key="m.value" :value="m.value">{{ m.label }}</option>
          </select>
        </div>
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">条件类型</label>
          <select v-model="form.conditionType" class="input">
            <option value="BASELINE_DEVIATION">同比偏差（较昨日同时段）</option>
            <option value="ABSOLUTE">绝对阈值</option>
          </select>
        </div>
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">方向</label>
          <select v-model="form.comparison" class="input">
            <option v-if="form.conditionType === 'BASELINE_DEVIATION'" value="BOTH">双向 ±N%</option>
            <option value="LT">{{ form.conditionType === 'ABSOLUTE' ? '低于' : '仅下跌' }}</option>
            <option value="GT">{{ form.conditionType === 'ABSOLUTE' ? '高于' : '仅上涨' }}</option>
          </select>
        </div>
        <div v-if="form.conditionType === 'ABSOLUTE'">
          <label class="block text-sm font-medium text-gray-700 mb-1">阈值</label>
          <input v-model.number="form.threshold" type="number" step="any" class="input" placeholder="如 1000" />
        </div>
        <div v-else>
          <label class="block text-sm font-medium text-gray-700 mb-1">偏差百分比（正数）</label>
          <input v-model.number="form.deviationPct" type="number" min="0" step="any" class="input" />
        </div>
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">评估窗口</label>
          <select v-model="form.window" class="input">
            <option value="TODAY">今日至今 vs 昨日同时段</option>
            <option value="HOUR_1">近 1 小时 vs 昨日同一小时</option>
          </select>
        </div>
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">级别</label>
          <select v-model="form.severity" class="input">
            <option v-for="s in Object.keys(severityColors)" :key="s" :value="s">{{ s }}</option>
          </select>
        </div>
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">环境（留空 = 全部）</label>
          <input v-model="form.environment" class="input" placeholder="prod" />
        </div>
        <div class="flex items-center gap-6 lg:col-span-3">
          <label class="flex items-center gap-2 text-sm text-gray-600">
            <input type="checkbox" v-model="form.enabled" class="rounded border-gray-300 text-primary-600" />
            启用规则
          </label>
          <label class="flex items-center gap-2 text-sm text-gray-600">
            <input type="checkbox" v-model="form.notifyWebhook" class="rounded border-gray-300 text-primary-600" />
            触发时发送 Webhook
          </label>
          <button @click="saveRule" class="btn btn-primary ml-auto" :disabled="saving">
            {{ saving ? '保存中...' : '保存规则' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 试算结果 -->
    <div v-if="evalResult" class="card mb-8 text-sm">
      <span class="font-medium text-gray-900">试算「{{ evalResult.rule.name }}」：</span>
      <template v-if="evalResult.available === false">
        <span class="text-gray-500">ClickHouse 未配置，评估不可用（规则配置不受影响）</span>
      </template>
      <template v-else>
        <span class="text-gray-600 ml-2">
          当前值 {{ fmtValue(evalResult.currentValue) }}
          <template v-if="evalResult.baseline != null">，基线 {{ fmtValue(evalResult.baseline) }}，偏差
            {{ Number(evalResult.deviationPct).toFixed(1) }}%</template>
          ，
        </span>
        <span :class="evalResult.fired ? 'text-red-600 font-medium' : 'text-green-600 font-medium'">
          {{ evalResult.fired ? '命中告警条件' : '未命中' }}
        </span>
      </template>
    </div>

    <!-- 规则列表 -->
    <div class="card mb-8 overflow-x-auto">
      <h3 class="text-base font-medium text-gray-900 mb-4">告警规则（{{ rules.length }}）</h3>
      <table v-if="rules.length" class="min-w-full divide-y divide-gray-200 text-sm">
        <thead>
          <tr class="text-left text-xs text-gray-500 uppercase">
            <th class="py-2 pr-4">名称</th>
            <th class="py-2 pr-4">条件</th>
            <th class="py-2 pr-4">窗口</th>
            <th class="py-2 pr-4">级别</th>
            <th class="py-2 pr-4">最近值</th>
            <th class="py-2 pr-4">状态</th>
            <th class="py-2 pr-4">最近评估</th>
            <th class="py-2">操作</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-gray-100">
          <tr v-for="rule in rules" :key="rule.id">
            <td class="py-3 pr-4 font-medium text-gray-900">
              {{ rule.name }}
              <span v-if="rule.environment" class="ml-1 text-xs text-gray-400">@{{ rule.environment }}</span>
            </td>
            <td class="py-3 pr-4 text-gray-600">
              {{ metricOptions.find((m) => m.value === rule.metricType)?.label || rule.metricType }}<br />
              <span class="text-xs">{{ conditionText(rule) }}</span>
            </td>
            <td class="py-3 pr-4 text-gray-600">{{ windowLabels[rule.window] || rule.window }}</td>
            <td class="py-3 pr-4">
              <span class="px-2 py-0.5 rounded-full text-xs font-medium" :class="severityColors[rule.severity]">
                {{ rule.severity }}
              </span>
            </td>
            <td class="py-3 pr-4 text-gray-600">{{ fmtValue(rule.lastValue) }}</td>
            <td class="py-3 pr-4">
              <span :class="rule.lastState === 'FIRING' ? 'text-red-600 font-medium' : 'text-green-600'">
                {{ rule.lastState === 'FIRING' ? 'FIRING' : 'OK' }}
              </span>
              <span class="ml-1 text-xs" :class="rule.enabled ? 'text-gray-400' : 'text-gray-400'">
                {{ rule.enabled ? '' : '（已停用）' }}
              </span>
            </td>
            <td class="py-3 pr-4 text-gray-500 text-xs">{{ fmtTime(rule.lastEvaluatedAt) }}</td>
            <td class="py-3 flex items-center gap-2 whitespace-nowrap">
              <button @click="toggleRule(rule)" class="text-xs text-blue-600 hover:underline">
                {{ rule.enabled ? '停用' : '启用' }}
              </button>
              <button @click="evaluateRule(rule)" class="text-xs text-blue-600 hover:underline">试算</button>
              <button @click="deleteRule(rule)" class="text-xs text-red-600 hover:underline">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else class="text-sm text-gray-400 py-6 text-center">暂无规则，点击右上角「新建规则」创建第一条告警</p>
    </div>

    <!-- 告警历史 -->
    <div class="card overflow-x-auto">
      <h3 class="text-base font-medium text-gray-900 mb-4">告警历史（{{ alerts.length }}）</h3>
      <table v-if="alerts.length" class="min-w-full divide-y divide-gray-200 text-sm">
        <thead>
          <tr class="text-left text-xs text-gray-500 uppercase">
            <th class="py-2 pr-4">告警</th>
            <th class="py-2 pr-4">级别</th>
            <th class="py-2 pr-4">状态</th>
            <th class="py-2 pr-4">指标值</th>
            <th class="py-2 pr-4">次数</th>
            <th class="py-2 pr-4">最近发生</th>
            <th class="py-2">操作</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-gray-100">
          <tr v-for="alert in alerts" :key="alert.id">
            <td class="py-3 pr-4">
              <span class="font-medium text-gray-900">{{ alert.title }}</span>
              <p class="text-xs text-gray-500 mt-0.5">{{ alert.description }}</p>
            </td>
            <td class="py-3 pr-4">
              <span class="px-2 py-0.5 rounded-full text-xs font-medium" :class="severityColors[alert.severity]">
                {{ alert.severity }}
              </span>
            </td>
            <td class="py-3 pr-4">
              <span class="px-2 py-0.5 rounded-full text-xs font-medium" :class="statusColors[alert.alertStatus]">
                {{ alert.alertStatus }}
              </span>
            </td>
            <td class="py-3 pr-4 text-gray-600">{{ fmtValue(alert.metricValue) }}</td>
            <td class="py-3 pr-4 text-gray-600">×{{ alert.occurrenceCount ?? 1 }}</td>
            <td class="py-3 pr-4 text-gray-500 text-xs">{{ fmtTime(alert.lastOccurredAt) }}</td>
            <td class="py-3 flex items-center gap-2 whitespace-nowrap">
              <template v-if="['OPEN', 'SNOOZED'].includes(alert.alertStatus)">
                <button @click="openAction(alert, 'acknowledge')" class="text-xs text-blue-600 hover:underline">确认</button>
                <button @click="openAction(alert, 'resolve')" class="text-xs text-green-600 hover:underline">解决</button>
              </template>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else class="text-sm text-gray-400 py-6 text-center">暂无告警记录</p>
    </div>

    <!-- 确认/解决弹层 -->
    <div v-if="actionTarget" class="fixed inset-0 z-50 flex items-center justify-center bg-black/40" @click.self="actionTarget = null">
      <div class="bg-white rounded-lg shadow-xl p-6 w-full max-w-md mx-4">
        <h3 class="text-base font-medium text-gray-900 mb-2">
          {{ actionTarget.action === 'acknowledge' ? '确认告警' : '解决告警' }}
        </h3>
        <p class="text-sm text-gray-500 mb-4">{{ actionTarget.alert.title }}</p>
        <textarea v-model="actionComment" rows="3" class="input mb-4" placeholder="备注（可选）"></textarea>
        <div class="flex justify-end gap-3">
          <button @click="actionTarget = null" class="btn btn-secondary">取消</button>
          <button @click="submitAction" class="btn btn-primary">提交</button>
        </div>
      </div>
    </div>
  </div>
</template>
