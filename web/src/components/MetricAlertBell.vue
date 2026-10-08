<script setup>
import { ref, reactive } from 'vue'
import api from '@/services/api'
import { useGameList } from '@/composables/useGameList'

// 图表级告警入口（Amplitude 铃铛式交互，调研「告警配置贴图表」落点）：
// 铃铛按钮 + 快速建规则弹层，规则本体走既有 /api/games/{gameId}/alert-rules。
// 规则的启停/历史/通道管理仍在业务告警页（AlertsView）。
const props = defineProps({
  // DAU | REVENUE | CRASH_RATE（与 MetricAlertService 支持的指标一一对应）
  metricType: { type: String, required: true },
  // 铃铛悬浮提示
  title: { type: String, default: '为此图表配置告警' }
})

const { currentGameId } = useGameList()

const open = ref(false)
const saving = ref(false)
const error = ref('')
const justCreated = ref(false)

const metricLabels = { DAU: 'DAU', REVENUE: '收入', CRASH_RATE: '崩溃率' }
const comparisonLabels = { GT: '高于', LT: '低于', BOTH: '双向 ±' }
const windowLabels = { TODAY: '今日至今', HOUR_1: '近 1 小时' }

const form = reactive({
  name: '',
  conditionType: 'BASELINE_DEVIATION',
  comparison: 'LT',
  threshold: null,
  deviationPct: 30,
  environment: '',
  window: 'TODAY',
  severity: 'WARNING',
  notifyWebhook: true
})

function show() {
  Object.assign(form, {
    name: `${metricLabels[props.metricType] || props.metricType}告警`,
    conditionType: 'BASELINE_DEVIATION',
    comparison: 'LT',
    threshold: null,
    deviationPct: 30,
    environment: '',
    window: 'TODAY',
    severity: 'WARNING',
    notifyWebhook: true
  })
  error.value = ''
  open.value = true
}

async function save() {
  if (!form.name.trim()) {
    error.value = '请填写规则名称'
    return
  }
  saving.value = true
  error.value = ''
  try {
    await api.post(`/api/games/${currentGameId.value}/alert-rules`, {
      name: form.name.trim(),
      metricType: props.metricType,
      conditionType: form.conditionType,
      comparison: form.comparison,
      threshold: form.conditionType === 'ABSOLUTE' ? Number(form.threshold) : null,
      deviationPct: form.conditionType === 'BASELINE_DEVIATION' ? Number(form.deviationPct) : null,
      environment: form.environment.trim() || null,
      window: form.window,
      severity: form.severity,
      enabled: true,
      notifyWebhook: form.notifyWebhook
    })
    open.value = false
    justCreated.value = true
    setTimeout(() => { justCreated.value = false }, 2500)
  } catch (e) {
    error.value = e.response?.data?.message || '创建告警规则失败'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <span class="inline-flex items-center">
    <button
      @click="show"
      class="p-1.5 text-gray-400 hover:text-primary-600 hover:bg-gray-100 rounded transition-colors"
      :title="title"
      aria-label="配置告警"
    >
      <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
          d="M15 17h5l-1.405-1.405A2.032 2.032 0 0118 14.158V11a6.002 6.002 0 00-4-5.659V5a2 2 0 10-4 0v.341C7.67 6.165 6 8.388 6 11v3.159c0 .538-.214 1.055-.595 1.436L4 17h5m6 0v1a3 3 0 11-6 0v-1m6 0H9" />
      </svg>
    </button>
    <span v-if="justCreated" class="ml-1 text-xs text-green-600">已创建</span>
  </span>

  <div v-if="open" class="fixed inset-0 z-50 flex items-center justify-center bg-black/40" @click.self="open = false">
    <div class="bg-white rounded-lg shadow-xl p-6 w-full max-w-lg mx-4">
      <h3 class="text-base font-medium text-gray-900 mb-4">
        新建{{ metricLabels[metricType] || metricType }}告警
      </h3>

      <p v-if="error" class="mb-3 text-sm text-red-600">{{ error }}</p>

      <div class="grid grid-cols-2 gap-4">
        <div class="col-span-2">
          <label class="block text-sm font-medium text-gray-700 mb-1">规则名称</label>
          <input v-model="form.name" class="input" placeholder="如：收入骤降告警" />
        </div>
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">条件</label>
          <select v-model="form.conditionType" class="input">
            <option value="BASELINE_DEVIATION">较昨日同时段偏差</option>
            <option value="ABSOLUTE">固定阈值</option>
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
            <option>INFO</option>
            <option>WARNING</option>
            <option>ERROR</option>
            <option>CRITICAL</option>
            <option>EMERGENCY</option>
          </select>
        </div>
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">环境（留空 = 全部）</label>
          <input v-model="form.environment" class="input" placeholder="prod" />
        </div>
        <div class="flex items-center col-span-2">
          <label class="flex items-center gap-2 text-sm text-gray-600">
            <input type="checkbox" v-model="form.notifyWebhook" class="rounded border-gray-300 text-primary-600" />
            触发时发送 Webhook
          </label>
          <div class="ml-auto flex gap-3">
            <button @click="open = false" class="btn btn-secondary">取消</button>
            <button @click="save" class="btn btn-primary" :disabled="saving">
              {{ saving ? '保存中...' : '创建规则' }}
            </button>
          </div>
        </div>
      </div>

      <p class="mt-3 text-xs text-gray-400">
        规则的启停、历史与通道管理在「业务告警」页
      </p>
    </div>
  </div>
</template>
