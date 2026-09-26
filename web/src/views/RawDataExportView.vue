<script setup>
import { ref, watch, onMounted } from 'vue'
import api from '@/services/api'
import GameSelector from '@/components/GameSelector.vue'
import { useGameList } from '@/composables/useGameList'

const { currentGameId } = useGameList()

const days = ref([])
const loading = ref(false)
const error = ref('')
const success = ref('')
const exporting = ref(false)

const form = ref({ environment: 'prod', date: '', compress: true })

async function load() {
  if (!currentGameId.value) return
  loading.value = true
  error.value = ''
  try {
    const res = await api.get(`/api/games/${currentGameId.value}/events-export`)
    days.value = res.data
  } catch (e) {
    error.value = e.response?.data?.message || '加载导出分区失败'
    console.error('Failed to load export partitions:', e)
  } finally {
    loading.value = false
  }
}

async function exportDay() {
  error.value = ''
  success.value = ''
  if (!form.value.date) {
    error.value = '请选择导出日期'
    return
  }
  exporting.value = true
  try {
    const res = await api.post(`/api/games/${currentGameId.value}/events-export`, {
      environment: form.value.environment,
      date: form.value.date,
      compress: form.value.compress
    })
    success.value = `导出完成：${Number(res.data.rows ?? 0).toLocaleString()} 行 / ${(Number(res.data.bytes ?? 0) / 1024 / 1024).toFixed(2)} MB（${res.data.format}）`
    await load()
  } catch (e) {
    error.value = e.response?.data?.message || '导出失败'
  } finally {
    exporting.value = false
  }
}

function fmtBytes(v) {
  const n = Number(v ?? 0)
  if (n >= 1 << 30) return (n / (1 << 30)).toFixed(2) + ' GB'
  if (n >= 1 << 20) return (n / (1 << 20)).toFixed(2) + ' MB'
  if (n >= 1024) return (n / 1024).toFixed(1) + ' KB'
  return n + ' B'
}

onMounted(load)
watch(currentGameId, load)
</script>

<template>
  <div>
    <div class="sm:flex sm:items-center mb-8 gap-4">
      <div class="sm:flex-auto">
        <h1 class="text-2xl font-bold text-gray-900">原始数据导出</h1>
        <p class="mt-1 text-sm text-gray-500">events 按日分区导出 JSONL（gzip 可选）到导出目录，供 Superset / Metabase 下钻</p>
      </div>
      <div class="mt-4 sm:mt-0 sm:ml-16 flex items-center gap-3 flex-wrap">
        <GameSelector />
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

    <!-- 触发导出 -->
    <div class="card mb-8">
      <h3 class="text-base font-medium text-gray-900 mb-4">导出一个日分区</h3>
      <div class="flex flex-wrap items-end gap-3">
        <div>
          <label class="block text-xs font-medium text-gray-500 mb-1">环境</label>
          <select v-model="form.environment" class="input !w-auto">
            <option value="dev">dev</option>
            <option value="staging">staging</option>
            <option value="prod">prod</option>
          </select>
        </div>
        <div>
          <label class="block text-xs font-medium text-gray-500 mb-1">日期</label>
          <input v-model="form.date" type="date" class="input !w-auto" />
        </div>
        <label class="flex items-center gap-2 text-sm text-gray-600 pb-2">
          <input type="checkbox" v-model="form.compress" class="rounded border-gray-300 text-primary-600 focus:ring-primary-500" />
          gzip 压缩
        </label>
        <button @click="exportDay" class="btn btn-primary" :disabled="exporting">
          {{ exporting ? '导出中…' : '开始导出' }}
        </button>
      </div>
      <p class="mt-3 text-xs text-gray-400">
        单分区上限 500 万行（分批游标读取）；每次导出附带 manifest.json（行数 / 字节数 / SHA-256），同日重复导出原子覆盖。
      </p>
    </div>

    <!-- 已导出分区列表 -->
    <div v-if="loading && days.length === 0" class="text-center py-12">
      <div class="animate-spin rounded-full h-12 w-12 border-b-2 border-primary-600 mx-auto"></div>
      <p class="mt-4 text-gray-500">加载中...</p>
    </div>

    <div v-else-if="days.length === 0" class="card text-center py-12">
      <p class="text-gray-500">还没有导出分区</p>
      <p class="text-xs text-gray-400 mt-1">选择环境与日期，导出第一个日分区</p>
    </div>

    <div v-else class="card overflow-x-auto">
      <table class="min-w-full divide-y divide-gray-200">
        <thead>
          <tr class="text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
            <th class="px-4 py-3">日期</th>
            <th class="px-4 py-3">环境</th>
            <th class="px-4 py-3">格式</th>
            <th class="px-4 py-3">行数</th>
            <th class="px-4 py-3">大小</th>
            <th class="px-4 py-3">SHA-256</th>
            <th class="px-4 py-3">导出时间</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-gray-100">
          <tr v-for="d in days" :key="d.date" class="text-sm text-gray-900">
            <td class="px-4 py-3 font-medium">{{ d.date }}</td>
            <td class="px-4 py-3">{{ d.environment }}</td>
            <td class="px-4 py-3 text-xs text-gray-500">{{ d.format }}</td>
            <td class="px-4 py-3 font-mono">{{ Number(d.rows ?? 0).toLocaleString() }}</td>
            <td class="px-4 py-3 font-mono">{{ fmtBytes(d.bytes) }}</td>
            <td class="px-4 py-3 font-mono text-xs text-gray-400" :title="d.sha256">{{ String(d.sha256 || '').slice(0, 12) }}…</td>
            <td class="px-4 py-3 text-xs text-gray-500">{{ String(d.generated_at || '').replace('T', ' ').slice(0, 19) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
