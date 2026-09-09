<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import api from '@/services/api'
import GameSelector from '@/components/GameSelector.vue'
import TrendChart from '@/components/TrendChart.vue'
import { useGameList } from '@/composables/useGameList'

const { currentGameId } = useGameList()

const days = ref(14)
const groups = ref([])
const trendPoints = ref([])
const versions = ref([])
const loading = ref(false)
const error = ref('')

// 符号化工具
const symbolicateInput = ref('')
const symbolicatePlatform = ref('android')
const symbolicateResult = ref(null)
const symbolicating = ref(false)

const dayOptions = [7, 14, 30, 90]

const trendLabels = computed(() => trendPoints.value.map((p) => p.date))
const trendSeries = computed(() => [
  { name: '崩溃次数', color: '#ef4444', values: trendPoints.value.map((p) => p.crashes) },
  { name: '影响设备数', color: '#f59e0b', values: trendPoints.value.map((p) => p.affectedDevices) }
])

async function load() {
  if (!currentGameId.value) return
  loading.value = true
  error.value = ''
  try {
    const [groupsResp, trendResp, versionResp] = await Promise.all([
      api.get(`/api/crash-metrics/${currentGameId.value}/top-groups`, { params: { days: days.value } }),
      api.get(`/api/crash-metrics/${currentGameId.value}/trend`, { params: { days: days.value } }),
      api.get(`/api/crash-metrics/${currentGameId.value}/rate-by-version`, { params: { days: days.value } })
    ])
    groups.value = groupsResp.data.groups || []
    trendPoints.value = trendResp.data.points || []
    versions.value = versionResp.data.versions || []
  } catch (e) {
    error.value = e.response?.data?.message || '加载 Crash 指标失败'
    console.error('Failed to load crash metrics:', e)
  } finally {
    loading.value = false
  }
}

async function symbolicate() {
  if (!symbolicateInput.value.trim()) return
  symbolicating.value = true
  symbolicateResult.value = null
  try {
    const response = await api.post(`/api/crash-metrics/${currentGameId.value}/symbolicate`, {
      platform: symbolicatePlatform.value,
      appVersion: '1.2.0',
      stackTrace: symbolicateInput.value
    })
    symbolicateResult.value = response.data
  } catch (e) {
    symbolicateResult.value = { error: e.response?.data || '符号化失败' }
  } finally {
    symbolicating.value = false
  }
}

function pct(v) {
  return `${(v * 100).toFixed(2)}%`
}

onMounted(load)
watch([currentGameId, days], load)
</script>

<template>
  <div>
    <div class="sm:flex sm:items-center mb-8 gap-4">
      <div class="sm:flex-auto">
        <h1 class="text-2xl font-bold text-gray-900">Crash / Error 监控</h1>
        <p class="mt-1 text-sm text-gray-500">崩溃分组（crash_hash 聚合）排行、趋势与版本崩溃率</p>
      </div>
      <div class="mt-4 sm:mt-0 sm:ml-16 flex items-center gap-3 flex-wrap">
        <GameSelector />
        <select v-model="days" class="input !w-auto">
          <option v-for="d in dayOptions" :key="d" :value="d">近 {{ d }} 天</option>
        </select>
        <button @click="load" class="btn btn-secondary">刷新</button>
      </div>
    </div>

    <div v-if="loading" class="text-center py-12">
      <div class="animate-spin rounded-full h-12 w-12 border-b-2 border-primary-600 mx-auto"></div>
      <p class="mt-4 text-gray-500">加载中...</p>
    </div>

    <div v-else-if="error" class="card text-center py-8 text-red-600">{{ error }}</div>

    <div v-else>
      <!-- Top 崩溃分组 -->
      <div class="card mb-8 overflow-x-auto">
        <h3 class="text-lg font-medium text-gray-900 mb-4">Top 崩溃分组</h3>
        <table class="table" v-if="groups.length > 0">
          <thead>
            <tr>
              <th>分组（crash_hash）</th>
              <th>示例信息</th>
              <th>发生次数</th>
              <th>影响设备</th>
              <th>首次出现</th>
              <th>最近出现</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="g in groups" :key="g.crashGroup">
              <td class="font-mono text-xs">{{ g.crashGroup }}</td>
              <td class="max-w-xs truncate">{{ g.sampleMessage || '-' }}</td>
              <td class="font-semibold text-red-600">{{ g.occurrences }}</td>
              <td>{{ g.affectedDevices }}</td>
              <td>{{ g.firstSeen }}</td>
              <td>{{ g.lastSeen }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else class="text-center py-6 text-gray-500">窗口期内无崩溃记录</p>
      </div>

      <!-- 趋势 -->
      <div class="card mb-8">
        <h3 class="text-lg font-medium text-gray-900 mb-4">崩溃趋势</h3>
        <TrendChart :labels="trendLabels" :series="trendSeries" />
      </div>

      <!-- 版本崩溃率 + 符号化工具 -->
      <div class="grid grid-cols-1 gap-5 lg:grid-cols-2">
        <div class="card overflow-x-auto">
          <h3 class="text-lg font-medium text-gray-900 mb-4">版本崩溃率</h3>
          <table class="table" v-if="versions.length > 0">
            <thead>
              <tr>
                <th>版本</th>
                <th>崩溃设备</th>
                <th>活跃设备</th>
                <th>崩溃率</th>
                <th>统计至</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="v in versions" :key="v.appVersion">
                <td class="font-medium">{{ v.appVersion }}</td>
                <td>{{ v.crashDevices }}</td>
                <td>{{ v.activeDevices }}</td>
                <td class="font-semibold" :class="v.avgCrashRate >= 0.05 ? 'text-red-600' : 'text-gray-900'">
                  {{ pct(v.avgCrashRate) }}
                </td>
                <td>{{ v.lastDate }}</td>
              </tr>
            </tbody>
          </table>
          <p v-else class="text-center py-6 text-gray-500">窗口期内无版本数据</p>
        </div>

        <div class="card">
          <h3 class="text-lg font-medium text-gray-900 mb-4">堆栈符号化</h3>
          <p class="text-xs text-gray-400 mb-3">
            粘贴混淆堆栈，按 (平台, 版本) 匹配 ACTIVE 符号映射规则执行正则替换
          </p>
          <select v-model="symbolicatePlatform" class="input mb-3">
            <option value="android">Android</option>
            <option value="ios">iOS</option>
            <option value="web">Web</option>
          </select>
          <textarea
            v-model="symbolicateInput"
            rows="6"
            class="input font-mono text-xs"
            placeholder="at a.b.CombatSystem.resolve(libgame.so+0x1f)&#10;at a.b.Loop.tick(libgame.so+0x2a)"
          ></textarea>
          <button @click="symbolicate" class="btn btn-primary mt-3" :disabled="symbolicating || !symbolicateInput.trim()">
            {{ symbolicating ? '符号化中...' : '符号化' }}
          </button>
          <div v-if="symbolicateResult" class="mt-4">
            <div v-if="symbolicateResult.error" class="text-sm text-red-600">{{ symbolicateResult.error }}</div>
            <div v-else>
              <p class="text-xs text-gray-400 mb-1">
                映射 {{ symbolicateResult.mappingId || '无（原样返回）' }} · 命中 {{ symbolicateResult.rulesApplied }} 条规则
              </p>
              <pre class="bg-gray-50 rounded-lg p-3 text-xs font-mono overflow-x-auto whitespace-pre-wrap">{{
                symbolicateResult.symbolized
              }}</pre>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
