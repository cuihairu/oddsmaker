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

// 创建/编辑弹层（edit 共用表单：flagKey 只读；名单/JSON 字段以文本编辑，提交按整表单语义）
const formOpen = ref(false)
const formMode = ref('create')
const formSaving = ref(false)
const formError = ref('')
const form = ref({})

const FLAG_TYPES = ['BOOLEAN', 'PERCENTAGE', 'WHITELIST', 'BLACKLIST', 'CONDITIONAL']

function toList(text) {
  return (text || '').split(/[,;\n]/).map(s => s.trim()).filter(Boolean)
}

function toText(v) {
  // 后端名单列是 JSON 数组文本（如 "[\"u1\",\"u10\"]"）→ 逗号分隔文本便于编辑
  if (Array.isArray(v)) return v.join(',')
  if (typeof v === 'string' && v.trim().startsWith('[')) {
    try { return JSON.parse(v).map(x => String(x)).join(',') } catch { return '' }
  }
  return ''
}

function openCreate() {
  formMode.value = 'create'
  form.value = {
    flagKey: '', flagName: '', description: '', category: '', owner: '',
    flagType: 'BOOLEAN', defaultValue: false,
    whitelistUsers: '', blacklistUsers: '', conditions: '', rolloutSteps: ''
  }
  formError.value = ''
  formOpen.value = true
}

function openEdit(flag) {
  formMode.value = 'edit'
  form.value = {
    flagKey: flag.flagKey,
    flagName: flag.flagName,
    description: flag.description || '',
    category: flag.category || '',
    owner: flag.owner || '',
    flagType: flag.flagType || 'BOOLEAN',
    defaultValue: !!flag.defaultValue,
    whitelistUsers: toText(flag.whitelistUsers),
    blacklistUsers: toText(flag.blacklistUsers),
    conditions: flag.conditions || '',
    rolloutSteps: flag.rolloutSteps || ''
  }
  formError.value = ''
  formOpen.value = true
}

async function submitForm() {
  formError.value = ''
  if (!form.value.flagName.trim()) {
    formError.value = '请填写开关名称'
    return
  }
  if (formMode.value === 'create' && !form.value.flagKey.trim()) {
    formError.value = '请填写开关键（flagKey）'
    return
  }
  formSaving.value = true
  try {
    const payload = {
      flagName: form.value.flagName.trim(),
      description: form.value.description,
      category: form.value.category,
      owner: form.value.owner,
      flagType: form.value.flagType,
      defaultValue: form.value.defaultValue,
      whitelistUsers: toList(form.value.whitelistUsers),
      blacklistUsers: toList(form.value.blacklistUsers),
      conditions: form.value.conditions,
      rolloutSteps: form.value.rolloutSteps
    }
    let res
    if (formMode.value === 'create') {
      payload.flagKey = form.value.flagKey.trim()
      payload.createdBy = authStore.userName || 'console'
      res = await api.post('/api/system/features', payload)
    } else {
      payload.modifiedBy = authStore.userName || 'console'
      res = await api.put(`/api/system/features/${form.value.flagKey}`, payload)
    }
    await load()
    formOpen.value = false
    success.value = formMode.value === 'create'
      ? `已创建「${res.data.flagName}」（新建为 DISABLED，请在列表中启用或配置灰度）`
      : `已更新「${res.data.flagName}」`
  } catch (e) {
    formError.value = e.response?.data?.message || (formMode.value === 'create' ? '创建失败' : '更新失败')
  } finally {
    formSaving.value = false
  }
}

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

async function advance(flag) {
  busyId.value = flag.id
  error.value = ''
  success.value = ''
  try {
    const res = await api.post(`/api/system/features/${flag.flagKey}/advance`, {
      modifiedBy: authStore.userName || 'console'
    })
    replaceRow(res.data)
    success.value = `灰度已推进：「${res.data.flagName}」步 ${res.data.currentStep ?? 0}，当前 ${res.data.percentageValue ?? 0}%`
  } catch (e) {
    error.value = e.response?.data?.message || '推进失败'
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
        <button @click="openCreate" class="btn btn-primary">新建开关</button>
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
              <button
                v-if="flag.flagStatus === 'STAGED_ROLLOUT'"
                @click="advance(flag)"
                class="text-xs text-yellow-700 hover:underline"
                :disabled="busyId === flag.id"
              >推进</button>
              <button @click="openPct(flag)" class="text-xs text-blue-600 hover:underline">灰度设置</button>
              <button @click="openEdit(flag)" class="text-xs text-gray-600 hover:underline">编辑</button>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else class="text-sm text-gray-400 py-6 text-center">
        {{ loading ? '加载中...' : '暂无功能开关（点右上「新建开关」创建）' }}
      </p>
    </div>

    <!-- 创建/编辑弹层（新建恒 DISABLED；启停/灰度走列表操作） -->
    <div
      v-if="formOpen"
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/40"
      @click.self="formOpen = false"
    >
      <div class="bg-white rounded-lg shadow-xl p-6 w-full max-w-lg mx-4 max-h-[90vh] overflow-y-auto">
        <div class="flex items-center justify-between mb-4">
          <h3 class="text-base font-medium text-gray-900">
            {{ formMode === 'create' ? '新建功能开关' : `编辑：${form.flagKey}` }}
          </h3>
          <button @click="formOpen = false" class="text-gray-400 hover:text-gray-600 text-sm">关闭</button>
        </div>
        <div class="grid grid-cols-2 gap-3">
          <div v-if="formMode === 'create'">
            <label class="block text-xs text-gray-500 mb-1">开关键（唯一）</label>
            <input v-model="form.flagKey" class="input w-full font-mono" :disabled="formSaving" />
          </div>
          <div>
            <label class="block text-xs text-gray-500 mb-1">名称</label>
            <input v-model="form.flagName" class="input w-full" :disabled="formSaving" />
          </div>
          <div>
            <label class="block text-xs text-gray-500 mb-1">类型</label>
            <select v-model="form.flagType" class="input w-full" :disabled="formSaving">
              <option v-for="t in FLAG_TYPES" :key="t" :value="t">{{ t }}</option>
            </select>
          </div>
          <div>
            <label class="block text-xs text-gray-500 mb-1">分类</label>
            <input v-model="form.category" class="input w-full" :disabled="formSaving" />
          </div>
          <div>
            <label class="block text-xs text-gray-500 mb-1">负责人</label>
            <input v-model="form.owner" class="input w-full" :disabled="formSaving" />
          </div>
          <div class="flex items-center mt-5">
            <input v-model="form.defaultValue" type="checkbox" class="mr-2" :disabled="formSaving" />
            <label class="text-xs text-gray-500">兜底默认值（名单/灰度未命中时返回）</label>
          </div>
          <div class="col-span-2">
            <label class="block text-xs text-gray-500 mb-1">描述</label>
            <input v-model="form.description" class="input w-full" :disabled="formSaving" />
          </div>
          <div class="col-span-2">
            <label class="block text-xs text-gray-500 mb-1">用户白名单（逗号分隔，精确成员匹配）</label>
            <textarea v-model="form.whitelistUsers" rows="2" class="input w-full font-mono" :disabled="formSaving"></textarea>
          </div>
          <div class="col-span-2">
            <label class="block text-xs text-gray-500 mb-1">用户黑名单（逗号分隔）</label>
            <textarea v-model="form.blacklistUsers" rows="2" class="input w-full font-mono" :disabled="formSaving"></textarea>
          </div>
          <div class="col-span-2">
            <label class="block text-xs text-gray-500 mb-1">灰度步骤（JSON 数组，如 [10,50,100]；清空提交=移除）</label>
            <input v-model="form.rolloutSteps" class="input w-full font-mono" :disabled="formSaving" />
          </div>
          <div class="col-span-2">
            <label class="block text-xs text-gray-500 mb-1">条件表达式（JSON 数组，attribute 限 user_id/game_id；清空提交=移除）</label>
            <textarea v-model="form.conditions" rows="2" class="input w-full font-mono" :disabled="formSaving"></textarea>
          </div>
        </div>
        <p class="text-xs text-gray-400 mt-3">
          新建恒为 DISABLED；启用/禁用与灰度放量请用列表操作（状态与百分比由专用端点独占）
        </p>
        <p v-if="formError" class="text-xs text-red-600 mt-2">{{ formError }}</p>
        <div class="flex justify-end gap-3 mt-5">
          <button @click="formOpen = false" class="btn btn-secondary" :disabled="formSaving">取消</button>
          <button @click="submitForm" class="btn btn-primary" :disabled="formSaving">
            {{ formSaving ? '保存中...' : formMode === 'create' ? '创建' : '保存' }}
          </button>
        </div>
      </div>
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
