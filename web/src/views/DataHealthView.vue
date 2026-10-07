<script setup>
import { ref, onMounted, computed } from 'vue'
import api from '@/services/api'

// B10 数据健康页（设计定稿 07-b10 §4.5）：五率读时算（服务端 /summary、/series 已产出），
// 本页只做展示与作用域选择；kafka_error 是平台故障，单列标记不摊进接入质量。
const games = ref([])
const gameId = ref('')
const environment = ref('prod')
const hours = ref(24)
const summary = ref(null)
const series = ref([])
const featureScopeKey = ref('')
const featureRows = ref([])
const featureLoading = ref(false)
const featureError = ref('')
const loading = ref(false)
const error = ref('')

const REJECT_LABELS = {
  rejectedSchema: 'schema 不符',
  rejectedUnknownEvent: '未登记事件',
  rejectedInvalidTimestamp: '时间戳非法',
  rejectedPiiBlocked: 'PII 拦截',
  rejectedPayloadTooLarge: '载荷超限',
  rejectedTrustEscalation: '信任自抬',
  rejectedBlocked: '封禁拦截',
  rejectedScopeMismatch: '作用域不符',
  rejectedKafkaError: 'Kafka 故障'
}

const identityOk = computed(() => summary.value && Number(summary.value.identityDelta) === 0)

onMounted(async () => {
  try {
    const resp = await api.get('/api/games')
    games.value = resp.data || []
    if (games.value.length > 0) {
      gameId.value = games.value[0].gameId || games.value[0].id
      await loadAll()
    }
  } catch (e) {
    error.value = '游戏列表加载失败'
  }
})

async function loadAll() {
  if (!gameId.value) return
  loading.value = true
  error.value = ''
  try {
    const params = { gameId: gameId.value, environment: environment.value, hours: hours.value }
    const [s, se] = await Promise.all([
      api.get('/api/data-quality/summary', { params }),
      api.get('/api/data-quality/series', { params })
    ])
    summary.value = s.data
    series.value = se.data || []
  } catch (e) {
    error.value = '数据质量指标加载失败'
    summary.value = null
    series.value = []
  } finally {
    loading.value = false
  }
}

async function loadFeatureStore() {
  featureError.value = ''
  if (!featureScopeKey.value.trim()) {
    featureError.value = 'scopeKey 不能为空（PLAYER:<user_id> / DEVICE:<device_id> / IP:<client_ip>）'
    return
  }
  featureLoading.value = true
  try {
    const resp = await api.get(`/api/feature-store/${encodeURIComponent(gameId.value)}/${encodeURIComponent(environment.value)}`, {
      params: { scopeKey: featureScopeKey.value.trim(), hours: hours.value }
    })
    featureRows.value = resp.data || []
  } catch (e) {
    featureError.value = 'feature_store 取数失败'
    featureRows.value = []
  } finally {
    featureLoading.value = false
  }
}

function pct(v) {
  return (Number(v) * 100).toFixed(2) + '%'
}

function fmtTime(ts) {
  return ts ? String(ts).replace('T', ' ').slice(0, 16) : '-'
}
</script>

<template>
  <div>
    <div class="sm:flex sm:items-center mb-8">
      <div class="sm:flex-auto">
        <h1 class="text-2xl font-bold text-gray-900">数据健康</h1>
        <p class="mt-1 text-sm text-gray-500">
          接入链路五项质量指标（5 分钟窗口，读时计算）与共享特征摘要
        </p>
      </div>
    </div>

    <!-- 作用域选择 -->
    <div class="card mb-6 p-4 flex flex-wrap items-end gap-4">
      <div>
        <label class="block text-sm font-medium text-gray-700">游戏</label>
        <select v-model="gameId" @change="loadAll" class="mt-1 input">
          <option v-for="g in games" :key="g.gameId || g.id" :value="g.gameId || g.id">
            {{ g.name || g.gameId || g.id }}
          </option>
        </select>
      </div>
      <div>
        <label class="block text-sm font-medium text-gray-700">环境</label>
        <input v-model="environment" @change="loadAll" class="mt-1 input w-32" placeholder="prod" />
      </div>
      <div>
        <label class="block text-sm font-medium text-gray-700">时间范围</label>
        <select v-model="hours" @change="loadAll" class="mt-1 input">
          <option :value="24">近 24 小时</option>
          <option :value="72">近 72 小时</option>
          <option :value="168">近 7 天</option>
        </select>
      </div>
      <button @click="loadAll" class="btn btn-secondary">刷新</button>
    </div>

    <div v-if="error" class="mb-6 rounded-md bg-red-50 p-4 text-sm text-red-700">{{ error }}</div>

    <div v-if="loading" class="text-center py-12">
      <div class="animate-spin rounded-full h-12 w-12 border-b-2 border-primary-600 mx-auto"></div>
      <p class="mt-4 text-gray-500">加载中...</p>
    </div>

    <template v-else-if="summary">
      <!-- 恒等式警示条：received ≠ accepted + Σrejected + sampled_out 时计数路径有漏分支 -->
      <div v-if="!identityOk"
           class="mb-6 rounded-md bg-yellow-50 border border-yellow-200 p-4 text-sm text-yellow-800">
        恒等式余项非零：received − (accepted + Σrejected + sampled_out) =
        <strong>{{ summary.identityDelta }}</strong>
        ——计数路径存在漏分支，请携带窗口时间排查网关埋点。
      </div>

      <!-- 五率卡片 -->
      <div class="grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-5 mb-8">
        <div v-for="m in [
              { label: '事件有效率', value: summary.eventValidRate, hint: 'accepted / received' },
              { label: '丢弃率', value: summary.dropRate, hint: 'Σrejected / received' },
              { label: '未知事件率', value: summary.unknownEventRate, hint: '未登记事件占比' },
              { label: '重复率', value: summary.duplicateRate, hint: '网关幂等吸收 + enrich 去重' },
              { label: '迟到率', value: summary.lateRate, hint: 'ts_server − ts_client > 5min' }
            ]" :key="m.label" class="card p-4">
          <p class="text-sm font-medium text-gray-500">{{ m.label }}</p>
          <p class="mt-1 text-2xl font-semibold text-gray-900">{{ pct(m.value) }}</p>
          <p class="mt-1 text-xs text-gray-400">{{ m.hint }}</p>
        </div>
      </div>

      <div class="grid grid-cols-1 lg:grid-cols-2 gap-6 mb-8">
        <!-- 汇总计数 + 拒绝原因 Top -->
        <div class="card p-4">
          <h3 class="text-lg font-medium text-gray-900 mb-3">汇总（近 {{ summary.hours }} 小时）</h3>
          <p class="text-sm text-gray-600 mb-4">
            received <strong>{{ summary.received }}</strong> ｜ accepted
            <strong>{{ summary.accepted }}</strong> ｜ sampled_out
            <strong>{{ summary.sampledOut }}</strong> ｜ 重复
            <strong>{{ Number(summary.duplicatesGateway) + Number(summary.duplicatesEnrich) }}</strong>
            （网关 {{ summary.duplicatesGateway }} / enrich {{ summary.duplicatesEnrich }}）｜ 迟到
            <strong>{{ summary.late }}</strong>
          </p>
          <h4 class="text-sm font-medium text-gray-700 mb-2">拒绝原因 Top</h4>
          <p v-if="!summary.rejectTop || summary.rejectTop.length === 0" class="text-sm text-gray-400">
            时间范围内无拒绝
          </p>
          <ul v-else class="divide-y divide-gray-100">
            <li v-for="r in summary.rejectTop" :key="r.reason" class="py-2 flex items-center justify-between text-sm">
              <span>
                {{ REJECT_LABELS[r.reason] || r.reason }}
                <span v-if="r.reason === 'rejectedKafkaError'"
                      class="ml-2 rounded bg-red-100 text-red-700 text-xs px-1.5 py-0.5">平台故障</span>
              </span>
              <span class="font-medium text-gray-900">{{ r.count }}</span>
            </li>
          </ul>
        </div>

        <!-- feature_store 取数 -->
        <div class="card p-4">
          <h3 class="text-lg font-medium text-gray-900 mb-3">共享特征摘要</h3>
          <div class="flex items-end gap-3 mb-3">
            <div class="flex-1">
              <label class="block text-sm font-medium text-gray-700">scopeKey</label>
              <input v-model="featureScopeKey" @keyup.enter="loadFeatureStore"
                     class="mt-1 input w-full font-mono text-sm" placeholder="PLAYER:u123" />
            </div>
            <button @click="loadFeatureStore" class="btn btn-secondary" :disabled="featureLoading">查询</button>
          </div>
          <p v-if="featureError" class="text-sm text-red-600">{{ featureError }}</p>
          <p v-if="featureLoading" class="text-sm text-gray-500">查询中...</p>
          <p v-else-if="featureRows.length === 0 && !featureError" class="text-sm text-gray-400">
            输入 scopeKey 查询窗口摘要（features 为扁平 JSON 串）
          </p>
          <div v-for="row in featureRows" :key="row.windowEnd" class="mb-3 rounded border border-gray-100 p-3">
            <p class="text-xs text-gray-500 font-mono">
              {{ row.scopeKey }} ｜ {{ fmtTime(row.windowStart) }} → {{ fmtTime(row.windowEnd) }} ｜ as_of {{ fmtTime(row.asOf) }}
            </p>
            <pre class="mt-1 text-xs text-gray-800 bg-gray-50 rounded p-2 overflow-x-auto">{{ row.features }}</pre>
          </div>
        </div>
      </div>

      <!-- 窗口序列 -->
      <div class="card overflow-hidden">
        <div class="px-4 py-3 border-b border-gray-100">
          <h3 class="text-lg font-medium text-gray-900">窗口序列（新窗在前）</h3>
        </div>
        <p v-if="series.length === 0" class="p-4 text-sm text-gray-400">时间范围内无窗口数据</p>
        <div v-else class="overflow-x-auto">
          <table class="min-w-full divide-y divide-gray-200 text-sm">
            <thead class="bg-gray-50">
              <tr>
                <th class="px-3 py-2 text-left font-medium text-gray-500">窗口起点</th>
                <th class="px-3 py-2 text-right font-medium text-gray-500">received</th>
                <th class="px-3 py-2 text-right font-medium text-gray-500">accepted</th>
                <th class="px-3 py-2 text-right font-medium text-gray-500">rejected</th>
                <th class="px-3 py-2 text-right font-medium text-gray-500">sampled</th>
                <th class="px-3 py-2 text-right font-medium text-gray-500">dup</th>
                <th class="px-3 py-2 text-right font-medium text-gray-500">late</th>
                <th class="px-3 py-2 text-right font-medium text-gray-500">有效率</th>
                <th class="px-3 py-2 text-right font-medium text-gray-500">丢弃率</th>
                <th class="px-3 py-2 text-right font-medium text-gray-500">恒等余项</th>
              </tr>
            </thead>
            <tbody class="divide-y divide-gray-100">
              <tr v-for="w in series" :key="w.windowStart" class="hover:bg-gray-50">
                <td class="px-3 py-2 font-mono text-xs text-gray-600">{{ fmtTime(w.windowStart) }}</td>
                <td class="px-3 py-2 text-right">{{ w.received }}</td>
                <td class="px-3 py-2 text-right">{{ w.accepted }}</td>
                <td class="px-3 py-2 text-right">{{ w.rejectedTotal }}</td>
                <td class="px-3 py-2 text-right">{{ w.sampledOut }}</td>
                <td class="px-3 py-2 text-right">{{ Number(w.duplicatesGateway) + Number(w.duplicatesEnrich) }}</td>
                <td class="px-3 py-2 text-right">{{ w.late }}</td>
                <td class="px-3 py-2 text-right">{{ pct(w.eventValidRate) }}</td>
                <td class="px-3 py-2 text-right">{{ pct(w.dropRate) }}</td>
                <td class="px-3 py-2 text-right"
                    :class="Number(w.identityDelta) === 0 ? 'text-gray-400' : 'text-red-600 font-medium'">
                  {{ w.identityDelta }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </template>

    <div v-else-if="!error" class="text-center py-12 text-sm text-gray-500">
      选择游戏后加载数据质量指标
    </div>
  </div>
</template>
