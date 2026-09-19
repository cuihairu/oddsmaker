<script setup>
import { ref, computed, onMounted } from 'vue'
import api from '@/services/api'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()

const flags = ref([])
const loading = ref(false)
const error = ref('')
const success = ref('')
const busyId = ref(null)      // 行级启用/禁用操作中
const pctTarget = ref(null)   // 灰度弹层对应的开关
const pctValue = ref(0)
const pctError = ref('')
const pctSaving = ref(false)

const statusColors = {
  ENABLED: 'bg-green-100 text-green-700',
  DISABLED: 'bg-gray-100 text-gray-500',
  CONDITIONAL: 'bg-blue-100 text-blue-700',
  STAGED_ROLLOUT: 'bg-yellow-100 text-yellow-700'
}

const stats = computed(() => ({
  total: flags.value.length,
  enabled: flags.value.filter(f => f.flagStatus === 'ENABLED').length,
  staged: flags.value.filter(f => f.flagStatus === 'STAGED_ROLLOUT').length,
  conditional: flags.value.filter(f => f.flagStatus === 'CONDITIONAL').length
}))

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await api.get('/api/system/features')
    flags.value = res.data
  } catch (e) {
    error.value = e.response?.data?.message || '加载功能开关失败'
    console.error('Failed to load feature flags:', e)
  } finally {
    loading.value = false
  }
}

function replaceRow(updated) {
  const i = flags.value.findIndex(f => f.id === updated.id)
  if (i !== -1) flags.value[i] = updated
}

async function toggle(flag, action) {
  busyId.value = flag.id
  error.value = ''
  success.value = ''
  try {
    const res = await api.post(`/api/system/features/${flag.flagKey}/${action}`, {
      modifiedBy: authStore.userName || 'console'
    })
    replaceRow(res.data)
    success.value = `已${action === 'enable' ? '启用' : '禁用'}「${flag.flagName}」`
  } catch (e) {
    error.value = e.response?.data?.message || '操作失败'
  } finally {
    busyId.value = null
  }
}

function openPct(flag) {
  pctTarget.value = flag
  pctValue.value = flag.percentageValue ?? 0
  pctError.value = ''
}

async function savePct() {
  pctError.value = ''
  pctSaving.value = true
  try {
    const res = await api.post(`/api/system/features/${pctTarget.value.flagKey}/percentage`, {
      percentage: pctValue.value,
      modifiedBy: authStore.userName || 'console'
    })
    replaceRow(res.data)
    success.value = `灰度已更新：「${res.data.flagName}」当前 ${res.data.percentageValue}%（${res.data.flagStatus}）`
    pctTarget.value = null
  } catch (e) {
    pctError.value = e.response?.data?.message || '灰度设置失败'
  } finally {
    pctSaving.value = false
  }
}

function fmtTime(ts) {
  return ts ? String(ts).replace('T', ' ').slice(0, 19) : '-'
}

onMounted(load)
</script>

<template>
  <div>
    <div class="sm:flex sm:items-center mb-8 gap-4">
      <div class="sm:flex-auto">
        <h1 class="text-2xl font-bold text-gray-900">功能开关</h1>
        <p class="mt-1 text-sm text-gray-500">
          平台级功能开关：启用/禁用、按用户确定性哈希灰度放量（FNV-1a 分桶，同用户结果稳定）
        </p>
      </div>
      <div class="mt-4 sm:mt-0 sm:ml-16 flex items-center gap-3 flex-wrap">
        <button @click="load" class="btn btn-secondary" :disabled="loading">刷新</button>
      </div>
    </div>

    <div v-if="error" class="card mb-6 text-sm text-red-600">{{ error }}</div>
    <div v-if="success" class="card mb-6 text-sm text-green-700">{{ success }}</div>

    <!-- 统计 -->
    <div class="grid grid-cols-2 md:grid-cols-4 gap-4 mb-8">
      <div class="card">
        <p class="text-xs text-gray-500 uppercase">开关总数</p>
        <p class="text-2xl font-bold text-gray-900 mt-1">{{ stats.total }}</p>
      </div>
      <div class="card">
        <p class="text-xs text-gray-500 uppercase">已启用</p>
        <p class="text-2xl font-bold text-green-600 mt-1">{{ stats.enabled }}</p>
      </div>
      <div class="card">
        <p class="text-xs text-gray-500 uppercase">灰度中</p>
        <p class="text-2xl font-bold text-yellow-600 mt-1">{{ stats.staged }}</p>
      </div>
      <div class="card">
        <p class="text-xs text-gray-500 uppercase">条件启用</p>
        <p class="text-2xl font-bold text-blue-600 mt-1">{{ stats.conditional }}</p>
      </div>
    </div>

    <!-- 开关列表 -->
    <div class="card overflow-x-auto">
      <h3 class="text-base font-medium text-gray-900 mb-4">功能开关（{{ flags.length }}）</h3>
      <table v-if="flags.length" class="min-w-full divide-y divide-gray-200 text-sm">
        <thead>
          <tr class="text-left text-xs text-gray-500 uppercase">
            <th class="py-2 pr-4">开关</th>
            <th class="py-2 pr-4">状态</th>
            <th class="py-2 pr-4">类型</th>
            <th class="py-2 pr-4">灰度</th>
            <th class="py-2 pr-4">过期时间</th>
            <th class="py-2">操作</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-gray-100">
          <tr v-for="flag in flags" :key="flag.id">
            <td class="py-3 pr-4 font-medium text-gray-900">
              {{ flag.flagName }}
              <p class="text-xs text-gray-400 mt-0.5 font-mono">{{ flag.flagKey }}</p>
            </td>
            <td class="py-3 pr-4">
              <span class="px-2 py-0.5 rounded-full text-xs font-medium" :class="statusColors[flag.flagStatus]">
                {{ flag.flagStatus }}
              </span>
            </td>
            <td class="py-3 pr-4 text-gray-400 text-xs">{{ flag.flagType }}</td>
            <td class="py-3 pr-4">
              <template v-if="flag.flagStatus === 'STAGED_ROLLOUT'">
                <div class="flex items-center gap-2">
                  <span class="text-gray-600 text-xs whitespace-nowrap">{{ flag.percentageValue ?? 0 }}%</span>
                  <div class="w-20 h-1.5 bg-gray-100 rounded-full overflow-hidden">
                    <div
                      class="h-full bg-yellow-400 rounded-full"
                      :style="{ width: (flag.percentageValue ?? 0) + '%' }"
                    ></div>
                  </div>
                  <span class="text-gray-400 text-xs">步 {{ flag.currentStep ?? 0 }}</span>
                </div>
              </template>
              <span v-else-if="flag.flagStatus === 'ENABLED'" class="text-gray-400 text-xs">100%</span>
              <span v-else class="text-gray-300 text-xs">-</span>
            </td>
            <td class="py-3 pr-4 text-gray-500 text-xs">{{ fmtTime(flag.expiryDate) }}</td>
            <td class="py-3 flex items-center gap-3 whitespace-nowrap">
              <button
                v-if="flag.flagStatus !== 'ENABLED'"
                @click="toggle(flag, 'enable')"
                class="text-xs text-green-600 hover:underline"
                :disabled="busyId === flag.id"
              >启用</button>
              <button
                v-if="flag.flagStatus !== 'DISABLED'"
                @click="toggle(flag, 'disable')"
                class="text-xs text-red-600 hover:underline"
                :disabled="busyId === flag.id"
              >禁用</button>
              <button @click="openPct(flag)" class="text-xs text-blue-600 hover:underline">灰度设置</button>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else class="text-sm text-gray-400 py-6 text-center">
        {{ loading ? '加载中...' : '暂无功能开关（可通过 API / seed 创建）' }}
      </p>
    </div>

    <!-- 灰度设置弹层 -->
    <div
      v-if="pctTarget"
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/40"
      @click.self="pctTarget = null"
    >
      <div class="bg-white rounded-lg shadow-xl p-6 w-full max-w-md mx-4">
        <div class="flex items-center justify-between mb-4">
          <h3 class="text-base font-medium text-gray-900">灰度设置：{{ pctTarget.flagName }}</h3>
          <button @click="pctTarget = null" class="text-gray-400 hover:text-gray-600 text-sm">关闭</button>
        </div>
        <label class="block text-xs text-gray-500 mb-1">放量百分比（0-100）</label>
        <input
          v-model.number="pctValue"
          type="number"
          min="0"
          max="100"
          class="input w-full"
          :disabled="pctSaving"
        />
        <p class="text-xs text-gray-400 mt-2">
          按用户哈希确定性分桶（同用户结果稳定）；设为 0 / 100 会自动转为 DISABLED / ENABLED
        </p>
        <p v-if="pctError" class="text-xs text-red-600 mt-2">{{ pctError }}</p>
        <div class="flex justify-end gap-3 mt-5">
          <button @click="pctTarget = null" class="btn btn-secondary" :disabled="pctSaving">取消</button>
          <button @click="savePct" class="btn btn-primary" :disabled="pctSaving">
            {{ pctSaving ? '保存中...' : '保存' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
