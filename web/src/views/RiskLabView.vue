<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import api from '@/services/api'
import GameSelector from '@/components/GameSelector.vue'
import { useGameList } from '@/composables/useGameList'

const { currentGameId } = useGameList()

const stats = ref(null)
const loading = ref(false)
const error = ref('')

// 规则样本下钻（选中规则 + 处置筛选，走案例回看列表 API）
const selectedRule = ref(null)
const sampleDisposition = ref('')
const samples = ref([])
const samplesLoading = ref(false)

const levelLabels = { LOW: '低', MEDIUM: '中', HIGH: '高', CRITICAL: '严重' }
const levelColors = {
  LOW: 'bg-gray-100 text-gray-700',
  MEDIUM: 'bg-blue-100 text-blue-700',
  HIGH: 'bg-orange-100 text-orange-700',
  CRITICAL: 'bg-red-600 text-white'
}
const statusLabels = {
  OPEN: '已建案', REVIEW: '人工审核', ALERT: '告警', MARK: '打标',
  THROTTLE: '限流', BLOCK: '封禁', RESOLVED: '已结案'
}
const dispositionLabels = {
  confirmed_benign: '误杀（确认正常）',
  confirmed_fraud: '确认违规',
  inconclusive: '证据不足'
}
const dispositionChips = [
  { value: '', label: '全部' },
  { value: 'confirmed_benign', label: '误杀' },
  { value: 'confirmed_fraud', label: '确认违规' },
  { value: 'inconclusive', label: '证据不足' }
]

const totals = computed(() => stats.value?.totals ?? {})
const rules = computed(() => stats.value?.rules ?? [])

async function load() {
  if (!currentGameId.value) return
  loading.value = true
  error.value = ''
  try {
    const res = await api.get(`/api/games/${currentGameId.value}/risk-lab/rule-stats`)
    stats.value = res.data
  } catch (e) {
    error.value = e.response?.data?.message || '加载策略实验室聚合失败'
    console.error('Failed to load risk lab stats:', e)
  } finally {
    loading.value = false
  }
}

async function selectRule(rule) {
  selectedRule.value = selectedRule.value?.ruleId === rule.ruleId ? null : rule
  if (selectedRule.value) {
    sampleDisposition.value = ''
    await loadSamples()
  }
}

async function loadSamples() {
  if (!currentGameId.value || !selectedRule.value) return
  samplesLoading.value = true
  try {
    const params = { ruleId: selectedRule.value.ruleId, limit: 100 }
    if (sampleDisposition.value) params.disposition = sampleDisposition.value
    const res = await api.get(`/api/games/${currentGameId.value}/risk-cases`, { params })
    samples.value = res.data
  } catch (e) {
    samples.value = []
    console.error('Failed to load rule samples:', e)
  } finally {
    samplesLoading.value = false
  }
}

function fmtTime(ts) {
  return ts ? String(ts).replace('T', ' ').slice(0, 19) : '-'
}

function fmtRate(rate) {
  return rate === null || rate === undefined ? '-' : `${rate}%`
}

watch(currentGameId, () => {
  stats.value = null
  selectedRule.value = null
  load()
})
onMounted(load)
</script>

<template>
  <div>
    <div class="sm:flex sm:items-center mb-8 gap-4">
      <div class="sm:flex-auto">
        <h1 class="text-2xl font-bold text-gray-900">策略实验室</h1>
        <p class="mt-1 text-sm text-gray-500">
          规则上线后的复盘聚合：按规则看案例量、误杀率（分母=已处置）与平均复盘时长；点规则行下钻案例样本
        </p>
      </div>
      <div class="mt-4 sm:mt-0 sm:ml-16">
        <button @click="load" class="btn btn-secondary" :disabled="loading">刷新</button>
      </div>
    </div>

    <div class="mb-6"><GameSelector /></div>

    <div v-if="error" class="card mb-6 text-sm text-red-600">{{ error }}</div>

    <!-- 汇总 -->
    <div class="grid grid-cols-2 md:grid-cols-4 gap-4 mb-8" v-if="stats">
      <div class="card">
        <p class="text-xs text-gray-500 uppercase">扫描案例（最近窗口）</p>
        <p class="text-2xl font-bold text-gray-900 mt-1">{{ stats.scannedCases }}</p>
      </div>
      <div class="card">
        <p class="text-xs text-gray-500 uppercase">误杀案例</p>
        <p class="text-2xl font-bold text-orange-600 mt-1">{{ totals.totalFalsePositives ?? 0 }}</p>
      </div>
      <div class="card">
        <p class="text-xs text-gray-500 uppercase">产生案例的规则</p>
        <p class="text-2xl font-bold text-gray-900 mt-1">{{ totals.rulesWithCases ?? 0 }}</p>
      </div>
      <div class="card">
        <p class="text-xs text-gray-500 uppercase">规则总数</p>
        <p class="text-2xl font-bold text-gray-900 mt-1">{{ rules.length }}</p>
      </div>
    </div>

    <!-- 规则聚合表 -->
    <div class="card overflow-x-auto mb-8">
      <h3 class="text-base font-medium text-gray-900 mb-4">规则复盘聚合</h3>
      <table v-if="rules.length" class="min-w-full divide-y divide-gray-200 text-sm">
        <thead>
          <tr class="text-left text-xs text-gray-500 uppercase">
            <th class="py-2 pr-4">规则</th>
            <th class="py-2 pr-4">风险分</th>
            <th class="py-2 pr-4">案例</th>
            <th class="py-2 pr-4">误杀</th>
            <th class="py-2 pr-4">确认违规</th>
            <th class="py-2 pr-4">证据不足</th>
            <th class="py-2 pr-4">未复盘</th>
            <th class="py-2 pr-4">误杀率</th>
            <th class="py-2 pr-4">平均复盘(h)</th>
            <th class="py-2">最近案例</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-gray-100">
          <tr
            v-for="rule in rules"
            :key="rule.ruleId"
            @click="selectRule(rule)"
            class="cursor-pointer hover:bg-gray-50"
            :class="{ 'bg-blue-50': selectedRule?.ruleId === rule.ruleId }"
          >
            <td class="py-3 pr-4 font-medium text-gray-900">
              {{ rule.ruleName || `（已删规则）${rule.ruleId}` }}
            </td>
            <td class="py-3 pr-4 text-gray-500">{{ rule.riskScore ?? '-' }}</td>
            <td class="py-3 pr-4 text-gray-900 font-medium">{{ rule.caseCount }}</td>
            <td class="py-3 pr-4" :class="rule.falsePositiveCount > 0 ? 'text-orange-600 font-medium' : 'text-gray-400'">
              {{ rule.falsePositiveCount }}
            </td>
            <td class="py-3 pr-4" :class="rule.confirmedFraudCount > 0 ? 'text-red-600' : 'text-gray-400'">
              {{ rule.confirmedFraudCount }}
            </td>
            <td class="py-3 pr-4 text-gray-500">{{ rule.inconclusiveCount }}</td>
            <td class="py-3 pr-4 text-gray-400">{{ rule.unreviewedCount }}</td>
            <td class="py-3 pr-4">
              <div v-if="rule.misKillRate !== null" class="flex items-center gap-2">
                <span class="text-gray-700 text-xs whitespace-nowrap">{{ rule.misKillRate }}%</span>
                <div class="w-16 h-1.5 bg-gray-100 rounded-full overflow-hidden">
                  <div class="h-full bg-orange-400 rounded-full" :style="{ width: rule.misKillRate + '%' }"></div>
                </div>
              </div>
              <span v-else class="text-gray-300 text-xs">-</span>
            </td>
            <td class="py-3 pr-4 text-gray-500">{{ rule.avgReviewHours ?? '-' }}</td>
            <td class="py-3 text-gray-500 text-xs whitespace-nowrap">{{ fmtTime(rule.lastCaseAt) }}</td>
          </tr>
        </tbody>
      </table>
      <p v-else class="text-sm text-gray-400 py-6 text-center">
        {{ loading ? '加载中...' : '暂无风控规则（去「风控规则」页创建）' }}
      </p>
    </div>

    <!-- 选中规则的案例样本下钻 -->
    <div v-if="selectedRule" class="card overflow-x-auto">
      <div class="flex items-center justify-between mb-4 flex-wrap gap-3">
        <h3 class="text-base font-medium text-gray-900">
          案例样本：{{ selectedRule.ruleName || selectedRule.ruleId }}（{{ samples.length }}）
        </h3>
        <div class="flex items-center gap-2">
          <button
            v-for="chip in dispositionChips"
            :key="chip.value"
            @click="sampleDisposition = chip.value; loadSamples()"
            class="px-2.5 py-1 rounded-full text-xs font-medium"
            :class="sampleDisposition === chip.value
              ? 'bg-blue-600 text-white'
              : 'bg-gray-100 text-gray-600 hover:bg-gray-200'"
          >{{ chip.label }}</button>
        </div>
      </div>
      <p v-if="samplesLoading" class="text-sm text-gray-400 py-4 text-center">加载中...</p>
      <table v-else-if="samples.length" class="min-w-full divide-y divide-gray-200 text-sm">
        <thead>
          <tr class="text-left text-xs text-gray-500 uppercase">
            <th class="py-2 pr-4">案件号</th>
            <th class="py-2 pr-4">等级</th>
            <th class="py-2 pr-4">判定</th>
            <th class="py-2 pr-4">处置结论</th>
            <th class="py-2 pr-4">对象</th>
            <th class="py-2">建案时间</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-gray-100">
          <tr v-for="rc in samples" :key="rc.id">
            <td class="py-3 pr-4 font-mono text-xs text-gray-900">{{ rc.caseNumber }}</td>
            <td class="py-3 pr-4">
              <span class="px-2 py-0.5 rounded-full text-xs font-medium" :class="levelColors[rc.riskLevel]">
                {{ levelLabels[rc.riskLevel] || rc.riskLevel }}
              </span>
            </td>
            <td class="py-3 pr-4 text-gray-600">{{ statusLabels[rc.status] || rc.status }}</td>
            <td class="py-3 pr-4 text-gray-600">{{ dispositionLabels[rc.disposition] || rc.disposition || '未复盘' }}</td>
            <td class="py-3 pr-4 text-gray-500 text-xs">{{ rc.targetType }}:{{ rc.targetId }}</td>
            <td class="py-3 text-gray-500 text-xs whitespace-nowrap">{{ fmtTime(rc.createdAt) }}</td>
          </tr>
        </tbody>
      </table>
      <p v-else class="text-sm text-gray-400 py-6 text-center">该筛选下暂无案例样本</p>
      <p class="text-xs text-gray-400 mt-3">
        样本过滤在最近 2000 条案例窗口内进行；证据上下文与误杀处置（解除封禁）请到「风控案例」页按案件号查看
      </p>
    </div>
  </div>
</template>
