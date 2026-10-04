<script setup>
import { ref, onMounted } from 'vue'
import api from '@/services/api'

const riskRules = ref([])
const loading = ref(true)

onMounted(async () => {
  await loadRiskRules()
})

async function loadRiskRules() {
  loading.value = true
  try {
    const response = await api.get('/api/risk-rules')
    riskRules.value = response.data.content || []
  } catch (error) {
    console.error('Failed to load risk rules:', error)
  } finally {
    loading.value = false
  }
}

function getRuleTypeColor(type) {
  const colors = {
    THRESHOLD: 'bg-red-100 text-red-800',
    VELOCITY: 'bg-orange-100 text-orange-800',
    BLACKLIST: 'bg-gray-100 text-gray-800',
    PATTERN: 'bg-purple-100 text-purple-800',
    FEATURE: 'bg-teal-100 text-teal-800'
  }
  return colors[type] || 'bg-gray-100 text-gray-800'
}

function getRuleTypeLabel(type) {
  const labels = {
    THRESHOLD: '阈值规则',
    VELOCITY: '速度规则',
    BLACKLIST: '黑名单',
    PATTERN: '模式规则',
    FEATURE: '特征规则'
  }
  return labels[type] || type
}
</script>

<template>
  <div>
    <div class="sm:flex sm:items-center mb-8">
      <div class="sm:flex-auto">
        <h1 class="text-2xl font-bold text-gray-900">风控规则</h1>
        <p class="mt-1 text-sm text-gray-500">
          管理风险控制规则
        </p>
      </div>
      <div class="mt-4 sm:mt-0 sm:ml-16 sm:flex-none">
        <button class="btn btn-primary">
          创建规则
        </button>
      </div>
    </div>

    <div v-if="loading" class="text-center py-12">
      <div class="animate-spin rounded-full h-12 w-12 border-b-2 border-primary-600 mx-auto"></div>
      <p class="mt-4 text-gray-500">加载中...</p>
    </div>

    <div v-else-if="riskRules.length === 0" class="text-center py-12">
      <svg class="mx-auto h-12 w-12 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4m5.618-4.016A11.955 11.955 0 0112 2.944a11.955 11.955 0 01-8.618 3.04A12.02 12.02 0 003 9c0 5.591 3.824 10.29 9 11.622 5.176-1.332 9-6.03 9-11.622 0-1.042-.133-2.052-.382-3.016z" />
      </svg>
      <h3 class="mt-2 text-sm font-medium text-gray-900">暂无风控规则</h3>
      <p class="mt-1 text-sm text-gray-500">创建一个风控规则来开始</p>
    </div>

    <div v-else class="card">
      <div class="overflow-x-auto">
        <table class="table">
          <thead>
            <tr>
              <th>规则名称</th>
              <th>游戏</th>
              <th>类型</th>
              <th>状态</th>
              <th>创建时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="rule in riskRules" :key="rule.id">
              <td class="font-medium">{{ rule.name }}</td>
              <td>{{ rule.gameId }}</td>
              <td>
                <span :class="[getRuleTypeColor(rule.type), 'badge']">
                  {{ getRuleTypeLabel(rule.type) }}
                </span>
              </td>
              <td>
                <span :class="rule.enabled ? 'bg-green-100 text-green-800' : 'bg-gray-100 text-gray-800'" class="badge">
                  {{ rule.enabled ? '启用' : '禁用' }}
                </span>
              </td>
              <td>{{ new Date(rule.createdAt).toLocaleDateString('zh-CN') }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>