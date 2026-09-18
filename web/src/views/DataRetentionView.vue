<script setup>
import { ref, onMounted } from 'vue'
import api from '@/services/api'

const configured = ref(false)
const enabled = ref(false)
const expectedDays = ref(null)
const items = ref([])
const loading = ref(false)
const running = ref(false)
const error = ref('')
const notice = ref('')

const statusColors = {
  IN_SYNC: 'bg-green-100 text-green-700',
  UPDATING: 'bg-blue-100 text-blue-700',
  FAILED: 'bg-red-100 text-red-700',
  SKIPPED_NO_CONFIG: 'bg-gray-100 text-gray-500',
  UNKNOWN: 'bg-orange-100 text-orange-700'
}
const statusLabels = {
  IN_SYNC: '已同步',
  UPDATING: '本轮已修正',
  FAILED: '失败',
  SKIPPED_NO_CONFIG: '无有效配置',
  UNKNOWN: '无法识别'
}

function fmtTime(ts) {
  return ts ? String(ts).replace('T', ' ').slice(0, 19) : '-'
}

function daysText(v) {
  return v == null ? '无 TTL' : v + ' 天'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await api.get('/api/retention/enforcements')
    applyState(res.data)
    items.value = res.data.items || []
  } catch (e) {
    error.value = e.response?.data || '加载数据保留状态失败'
    console.error('Failed to load retention state:', e)
  } finally {
    loading.value = false
  }
}

function applyState(data) {
  configured.value = data.configured
  enabled.value = data.enabled
  expectedDays.value = data.expectedDays
}

async function run() {
  if (!confirm('确认立即对账 ClickHouse 各表 TTL？漂移的表将执行 MODIFY TTL 修正，缩短保留期会触发后台清理历史数据（不可逆）。')) return
  running.value = true
  error.value = ''
  notice.value = ''
  try {
    const res = await api.post('/api/retention/enforcements/run')
    applyState(res.data)
    items.value = res.data.items || []
    const altered = items.value.filter(i => i.status === 'UPDATING').length
    notice.value = configured.value
      ? `对账完成：${altered} 张表已下发 TTL 修正，期望 ${expectedDays.value ?? '-'} 天`
      : 'ClickHouse 未配置（oddsmaker.clickhouse.url 为空），未执行任何动作'
  } catch (e) {
    error.value = e.response?.data || '手动对账失败'
  } finally {
    running.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="sm:flex sm:items-center mb-8 gap-4">
      <div class="sm:flex-auto">
        <h1 class="text-2xl font-bold text-gray-900">数据保留</h1>
        <p class="mt-1 text-sm text-gray-500">
          ClickHouse 表级 TTL 自动对账：期望值取全部游戏/环境 dataRetentionDays 的最小值（最严格承诺生效），
          漂移时逐表 ALTER ... MODIFY TTL 修正
        </p>
      </div>
      <div class="mt-4 sm:mt-0 sm:ml-16 flex items-center gap-3 flex-wrap">
        <button @click="load" class="btn btn-secondary" :disabled="loading">刷新</button>
        <button @click="run" class="btn btn-primary" :disabled="running">
          {{ running ? '对账中...' : '立即对账' }}
        </button>
      </div>
    </div>

    <div v-if="error" class="card mb-6 text-sm text-red-600">{{ error }}</div>
    <div v-if="notice" class="card mb-6 text-sm text-green-700">{{ notice }}</div>

    <!-- 概览卡 -->
    <div class="grid grid-cols-1 md:grid-cols-3 gap-4 mb-8">
      <div class="card">
        <p class="text-xs text-gray-500 uppercase">ClickHouse</p>
        <p class="mt-1 text-lg font-semibold" :class="configured ? 'text-gray-900' : 'text-red-600'">
          {{ configured ? '已配置' : '未配置' }}
        </p>
        <p class="text-xs text-gray-400 mt-1">未配置时对账整体跳过</p>
      </div>
      <div class="card">
        <p class="text-xs text-gray-500 uppercase">定时对账</p>
        <p class="mt-1 text-lg font-semibold" :class="enabled ? 'text-green-700' : 'text-gray-400'">
          {{ enabled ? '已启用' : '未启用' }}
        </p>
        <p class="text-xs text-gray-400 mt-1">
          {{ enabled ? '每小时自动对账修正' : '默认关闭（清理不可逆）——ODDSMAKER_RETENTION_ENABLED=true 开启' }}
        </p>
      </div>
      <div class="card">
        <p class="text-xs text-gray-500 uppercase">期望保留</p>
        <p class="mt-1 text-lg font-semibold text-gray-900">
          {{ expectedDays != null ? expectedDays + ' 天' : '无有效配置' }}
        </p>
        <p class="text-xs text-gray-400 mt-1">
          全部游戏与环境 dataRetentionDays 的最小值（7~3650 天夹取）
        </p>
      </div>
    </div>

    <!-- 表状态 -->
    <div class="card overflow-x-auto">
      <h3 class="text-base font-medium text-gray-900 mb-4">受管表（{{ items.length }}）</h3>
      <table v-if="items.length" class="min-w-full divide-y divide-gray-200 text-sm">
        <thead>
          <tr class="text-left text-xs text-gray-500 uppercase">
            <th class="py-2 pr-4">表</th>
            <th class="py-2 pr-4">TTL 基准列</th>
            <th class="py-2 pr-4">当前</th>
            <th class="py-2 pr-4">期望</th>
            <th class="py-2 pr-4">状态</th>
            <th class="py-2 pr-4">修正次数</th>
            <th class="py-2 pr-4">最近检查</th>
            <th class="py-2">错误</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-gray-100">
          <tr v-for="item in items" :key="item.chTable">
            <td class="py-3 pr-4 font-medium text-gray-900">{{ item.chTable }}</td>
            <td class="py-3 pr-4 text-gray-600">{{ item.ttlColumn }}</td>
            <td class="py-3 pr-4 text-gray-600">{{ daysText(item.actualDays) }}</td>
            <td class="py-3 pr-4 text-gray-600">{{ item.desiredDays != null ? item.desiredDays + ' 天' : '-' }}</td>
            <td class="py-3 pr-4">
              <span class="px-2 py-0.5 rounded-full text-xs font-medium" :class="statusColors[item.status]">
                {{ statusLabels[item.status] || item.status }}
              </span>
            </td>
            <td class="py-3 pr-4 text-gray-500 text-xs">{{ item.updateCount }}</td>
            <td class="py-3 pr-4 text-gray-500 text-xs">{{ fmtTime(item.lastCheckedAt) }}</td>
            <td class="py-3 text-xs text-red-500 max-w-xs break-all">{{ item.errorMessage || '' }}</td>
          </tr>
        </tbody>
      </table>
      <p v-else class="text-sm text-gray-400 py-6 text-center">
        暂无对账状态——点击「立即对账」生成，或等待定时任务
      </p>
    </div>
  </div>
</template>
