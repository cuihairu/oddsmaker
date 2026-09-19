<script setup>
import { ref, watch, onMounted } from 'vue'
import api from '@/services/api'
import GameSelector from '@/components/GameSelector.vue'
import { useGameList } from '@/composables/useGameList'

const { currentGameId } = useGameList()

const jobs = ref([])
const stats = ref(null)
const loading = ref(false)
const error = ref('')
const success = ref('')
const actingId = ref(null)   // 当前正在部署/停止/刷新的作业
const detailTarget = ref(null)  // 详情弹层对应的作业
const detail = ref(null)       // { config, rules }
const detailLoading = ref(false)

const statusColors = {
  DRAFT: 'bg-gray-100 text-gray-500',
  DEPLOYING: 'bg-blue-100 text-blue-700',
  RUNNING: 'bg-green-100 text-green-700',
  STOPPING: 'bg-yellow-100 text-yellow-700',
  STOPPED: 'bg-gray-100 text-gray-500',
  FAILED: 'bg-red-100 text-red-700',
  PAUSED: 'bg-yellow-100 text-yellow-700'
}

const JOB_TYPES = {
  RISK_EVALUATION: '风险评估',
  FRAUD_DETECTION: '欺诈检测',
  ANOMALY_DETECTION: '异常检测',
  REALTIME_AGGREGATION: '实时聚合',
  PATTERN_MATCHING: '模式匹配'
}

function canDeploy(job) {
  return ['DRAFT', 'STOPPED', 'FAILED'].includes(job.status)
}
function canStop(job) {
  return ['RUNNING', 'DEPLOYING'].includes(job.status)
}

async function load() {
  if (!currentGameId.value) return
  loading.value = true
  error.value = ''
  try {
    const [jobRes, statRes] = await Promise.all([
      api.get(`/api/flink-jobs/game/${currentGameId.value}`),
      api.get(`/api/flink-jobs/stats/${currentGameId.value}`)
    ])
    jobs.value = jobRes.data
    stats.value = statRes.data
  } catch (e) {
    error.value = e.response?.data?.message || '加载 Flink 作业失败'
    console.error('Failed to load flink jobs:', e)
  } finally {
    loading.value = false
  }
}

async function deploy(job) {
  if (!confirm(`确认部署作业「${job.displayName || job.name}」到 Flink 集群？将上传 jar 并提交执行。`)) return
  actingId.value = job.id
  error.value = ''
  success.value = ''
  try {
    await api.post(`/api/flink-jobs/${job.id}/deploy`, null, { params: { gameId: currentGameId.value } })
    await load()
    success.value = `作业「${job.displayName || job.name}」已提交集群`
  } catch (e) {
    error.value = e.response?.data?.message || '部署失败'
  } finally {
    actingId.value = null
  }
}

async function stop(job) {
  if (!confirm(`确认停止作业「${job.displayName || job.name}」？将向集群发送取消请求。`)) return
  actingId.value = job.id
  error.value = ''
  success.value = ''
  try {
    await api.post(`/api/flink-jobs/${job.id}/stop`, null, { params: { gameId: currentGameId.value } })
    await load()
    success.value = `作业「${job.displayName || job.name}」已停止`
  } catch (e) {
    error.value = e.response?.data?.message || '停止失败'
  } finally {
    actingId.value = null
  }
}

async function refreshStatus(job) {
  actingId.value = job.id
  error.value = ''
  success.value = ''
  try {
    const res = await api.post(`/api/flink-jobs/${job.id}/refresh`, null, {
      params: { gameId: currentGameId.value }
    })
    await load()
    success.value = `作业「${job.displayName || job.name}」状态已同步：${res.data.status}`
  } catch (e) {
    error.value = e.response?.data?.message || '状态同步失败'
  } finally {
    actingId.value = null
  }
}

async function openDetail(job) {
  detailTarget.value = job
  detail.value = null
  detailLoading.value = true
  try {
    const [cfgRes, ruleRes] = await Promise.all([
      api.get(`/api/flink-jobs/${job.id}/config`, { params: { gameId: currentGameId.value } }),
      api.get(`/api/flink-jobs/${job.id}/rules`, { params: { gameId: currentGameId.value } })
    ])
    detail.value = { config: cfgRes.data, rules: ruleRes.data }
  } catch (e) {
    error.value = e.response?.data?.message || '加载作业详情失败'
  } finally {
    detailLoading.value = false
  }
}

function fmtTime(ts) {
  return ts ? String(ts).replace('T', ' ').slice(0, 19) : '-'
}

onMounted(load)
watch(currentGameId, load)
</script>

<template>
  <div>
    <div class="sm:flex sm:items-center mb-8 gap-4">
      <div class="sm:flex-auto">
        <h1 class="text-2xl font-bold text-gray-900">Flink 作业</h1>
        <p class="mt-1 text-sm text-gray-500">
          实时计算作业管理：部署/停止走 Flink 集群 REST，状态可从集群同步对账
        </p>
      </div>
      <div class="mt-4 sm:mt-0 sm:ml-16 flex items-center gap-3 flex-wrap">
        <GameSelector />
        <button @click="load" class="btn btn-secondary" :disabled="loading">刷新</button>
      </div>
    </div>

    <div v-if="error" class="card mb-6 text-sm text-red-600">{{ error }}</div>
    <div v-if="success" class="card mb-6 text-sm text-green-700">{{ success }}</div>

    <!-- 统计 -->
    <div v-if="stats" class="grid grid-cols-2 md:grid-cols-4 gap-4 mb-8">
      <div class="card">
        <p class="text-xs text-gray-500 uppercase">作业总数</p>
        <p class="text-2xl font-bold text-gray-900 mt-1">{{ stats.totalJobs }}</p>
      </div>
      <div class="card">
        <p class="text-xs text-gray-500 uppercase">运行中</p>
        <p class="text-2xl font-bold text-green-600 mt-1">{{ stats.runningJobs }}</p>
      </div>
      <div class="card">
        <p class="text-xs text-gray-500 uppercase">已停止</p>
        <p class="text-2xl font-bold text-gray-900 mt-1">{{ stats.stoppedJobs }}</p>
      </div>
      <div class="card">
        <p class="text-xs text-gray-500 uppercase">失败</p>
        <p class="text-2xl font-bold text-red-600 mt-1">{{ stats.failedJobs }}</p>
      </div>
    </div>

    <!-- 作业列表 -->
    <div class="card overflow-x-auto">
      <h3 class="text-base font-medium text-gray-900 mb-4">作业列表（{{ jobs.length }}）</h3>
      <table v-if="jobs.length" class="min-w-full divide-y divide-gray-200 text-sm">
        <thead>
          <tr class="text-left text-xs text-gray-500 uppercase">
            <th class="py-2 pr-4">名称</th>
            <th class="py-2 pr-4">类型</th>
            <th class="py-2 pr-4">状态</th>
            <th class="py-2 pr-4">并行度</th>
            <th class="py-2 pr-4">Flink Job ID</th>
            <th class="py-2 pr-4">最近部署</th>
            <th class="py-2">操作</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-gray-100">
          <tr v-for="job in jobs" :key="job.id">
            <td class="py-3 pr-4 font-medium text-gray-900">
              {{ job.displayName || job.name }}
              <p v-if="job.errorMessage" class="text-xs text-red-500 mt-0.5 max-w-[260px] truncate" :title="job.errorMessage">
                {{ job.errorMessage }}
              </p>
            </td>
            <td class="py-3 pr-4 text-gray-600 text-xs">{{ JOB_TYPES[job.jobType] || job.jobType }}</td>
            <td class="py-3 pr-4">
              <span class="px-2 py-0.5 rounded-full text-xs font-medium" :class="statusColors[job.status]">
                {{ job.status }}
              </span>
            </td>
            <td class="py-3 pr-4 text-gray-600">{{ job.parallelism }}</td>
            <td class="py-3 pr-4 text-gray-500 text-xs max-w-[180px] truncate" :title="job.flinkUrl || ''">
              <a v-if="job.flinkUrl" :href="job.flinkUrl" target="_blank" class="text-blue-600 hover:underline">
                {{ job.flinkJobId }}
              </a>
              <span v-else>-</span>
            </td>
            <td class="py-3 pr-4 text-gray-500 text-xs">{{ fmtTime(job.deployedAt) }}</td>
            <td class="py-3 flex items-center gap-2 whitespace-nowrap">
              <button
                v-if="canDeploy(job)"
                @click="deploy(job)"
                class="text-xs text-blue-600 hover:underline"
                :disabled="actingId === job.id"
              >
                {{ actingId === job.id ? '提交中...' : '部署' }}
              </button>
              <button
                v-if="canStop(job)"
                @click="stop(job)"
                class="text-xs text-yellow-600 hover:underline"
                :disabled="actingId === job.id"
              >停止</button>
              <button
                v-if="job.flinkJobId"
                @click="refreshStatus(job)"
                class="text-xs text-blue-600 hover:underline"
                :disabled="actingId === job.id"
              >同步状态</button>
              <button @click="openDetail(job)" class="text-xs text-blue-600 hover:underline">详情</button>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else class="text-sm text-gray-400 py-6 text-center">
        {{ loading ? '加载中...' : '当前游戏暂无 Flink 作业记录（作业记录通过 API 创建）' }}
      </p>
    </div>

    <!-- 详情弹层 -->
    <div
      v-if="detailTarget"
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/40"
      @click.self="detailTarget = null"
    >
      <div class="bg-white rounded-lg shadow-xl p-6 w-full max-w-3xl mx-4 max-h-[85vh] overflow-y-auto">
        <div class="flex items-center justify-between mb-4">
          <h3 class="text-base font-medium text-gray-900">
            作业详情：{{ detailTarget.displayName || detailTarget.name }}
          </h3>
          <button @click="detailTarget = null" class="text-gray-400 hover:text-gray-600 text-sm">关闭</button>
        </div>
        <p v-if="detailLoading" class="text-sm text-gray-400 py-6 text-center">加载中...</p>
        <template v-else-if="detail">
          <h4 class="text-xs text-gray-500 uppercase mb-2">关联规则（{{ detail.rules.length }}）</h4>
          <ul v-if="detail.rules.length" class="text-sm text-gray-700 mb-6 space-y-1">
            <li v-for="rule in detail.rules" :key="rule.id">
              <span class="font-medium">{{ rule.name }}</span>
              <span class="text-gray-400 text-xs ml-2">{{ rule.id }}</span>
            </li>
          </ul>
          <p v-else class="text-sm text-gray-400 mb-6">未关联规则</p>

          <h4 class="text-xs text-gray-500 uppercase mb-2">作业配置</h4>
          <pre class="bg-gray-50 rounded p-3 text-xs text-gray-700 overflow-x-auto">{{ JSON.stringify(detail.config, null, 2) }}</pre>
        </template>
      </div>
    </div>
  </div>
</template>
