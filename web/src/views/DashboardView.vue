<script setup>
import { ref, onMounted } from 'vue'
import api from '@/services/api'

const stats = ref({
  totalGames: 0,
  activeGames: 0,
  totalEvents: 0,
  totalUsers: 0
})

const recentActivities = ref([])
const loading = ref(true)

onMounted(async () => {
  // 各请求独立落定：只读账号（演示 VIEWER 无 user:read）单个接口被拒时，
  // 其余卡片与最近活动仍要加载——原 Promise.all 一挂全挂，整屏停在全零空态。
  const [games, users, audit] = await Promise.allSettled([
    api.get('/api/games/statistics'),
    api.get('/api/users/statistics'),
    api.get('/api/audit-logs?size=10')
  ])

  if (games.status === 'fulfilled') {
    stats.value.totalGames = games.value.data.totalGames || 0
    stats.value.activeGames = games.value.data.liveGames || 0
  } else {
    console.error('Failed to load game statistics:', games.reason)
  }

  if (users.status === 'fulfilled') {
    stats.value.totalUsers = users.value.data.totalUsers || 0
  } else {
    console.error('Failed to load user statistics:', users.reason)
  }

  if (audit.status === 'fulfilled') {
    recentActivities.value = audit.value.data.content || []
  } else {
    console.error('Failed to load recent activities:', audit.reason)
  }

  loading.value = false
})

function formatDate(dateStr) {
  if (!dateStr) return '-'
  return new Date(dateStr).toLocaleString('zh-CN')
}

function getActionColor(action) {
  const colors = {
    CREATE: 'text-green-600',
    UPDATE: 'text-blue-600',
    DELETE: 'text-red-600',
    LOGIN: 'text-purple-600',
    LOGOUT: 'text-gray-600'
  }
  return colors[action] || 'text-gray-600'
}
</script>

<template>
  <div>
    <div class="mb-8">
      <h1 class="text-2xl font-bold text-gray-900">仪表盘</h1>
      <p class="mt-1 text-sm text-gray-500">
        欢迎使用 Oddsmaker 游戏分析与风控平台
      </p>
    </div>

    <!-- 统计卡片 -->
    <div class="grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-4 mb-8">
      <div class="card">
        <div class="flex items-center">
          <div class="flex-shrink-0">
            <div class="w-8 h-8 rounded-full bg-primary-100 flex items-center justify-center">
              <svg class="w-5 h-5 text-primary-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 4a2 2 0 114 0v1a1 1 0 001 1h3a1 1 0 011 1v3a1 1 0 01-1 1h-1a2 2 0 100 4h1a1 1 0 011 1v3a1 1 0 01-1 1h-3a1 1 0 01-1-1v-1a2 2 0 10-4 0v1a1 1 0 01-1 1H7a1 1 0 01-1-1v-3a1 1 0 00-1-1H4a2 2 0 110-4h1a1 1 0 001-1V7a1 1 0 011-1h3a1 1 0 001-1V4z" />
              </svg>
            </div>
          </div>
          <div class="ml-4">
            <p class="text-sm font-medium text-gray-500">总游戏数</p>
            <p class="text-2xl font-semibold text-gray-900">{{ stats.totalGames }}</p>
          </div>
        </div>
      </div>

      <div class="card">
        <div class="flex items-center">
          <div class="flex-shrink-0">
            <div class="w-8 h-8 rounded-full bg-green-100 flex items-center justify-center">
              <svg class="w-5 h-5 text-green-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
            </div>
          </div>
          <div class="ml-4">
            <p class="text-sm font-medium text-gray-500">活跃游戏</p>
            <p class="text-2xl font-semibold text-gray-900">{{ stats.activeGames }}</p>
          </div>
        </div>
      </div>

      <div class="card">
        <div class="flex items-center">
          <div class="flex-shrink-0">
            <div class="w-8 h-8 rounded-full bg-blue-100 flex items-center justify-center">
              <svg class="w-5 h-5 text-blue-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
              </svg>
            </div>
          </div>
          <div class="ml-4">
            <p class="text-sm font-medium text-gray-500">总事件数</p>
            <p class="text-2xl font-semibold text-gray-900">{{ stats.totalEvents }}</p>
          </div>
        </div>
      </div>

      <div class="card">
        <div class="flex items-center">
          <div class="flex-shrink-0">
            <div class="w-8 h-8 rounded-full bg-purple-100 flex items-center justify-center">
              <svg class="w-5 h-5 text-purple-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4.354a4 4 0 110 5.292M15 21H3v-1a6 6 0 0112 0v1zm0 0h6v-1a6 6 0 00-9-5.197M13 7a4 4 0 11-8 0 4 4 0 018 0z" />
              </svg>
            </div>
          </div>
          <div class="ml-4">
            <p class="text-sm font-medium text-gray-500">总用户数</p>
            <p class="text-2xl font-semibold text-gray-900">{{ stats.totalUsers }}</p>
          </div>
        </div>
      </div>
    </div>

    <div class="grid grid-cols-1 gap-5 lg:grid-cols-2">
      <!-- 快速操作 -->
      <div class="card">
        <h3 class="text-lg font-medium text-gray-900 mb-4">快速操作</h3>
        <div class="space-y-3">
          <router-link
            to="/games"
            class="flex items-center p-3 rounded-lg hover:bg-gray-50 transition-colors"
          >
            <div class="w-10 h-10 rounded-full bg-primary-100 flex items-center justify-center mr-3">
              <svg class="w-6 h-6 text-primary-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 6v6m0 0v6m0-6h6m-6 0H6" />
              </svg>
            </div>
            <div>
              <p class="text-sm font-medium text-gray-900">创建新游戏</p>
              <p class="text-xs text-gray-500">添加新的游戏项目</p>
            </div>
          </router-link>

          <router-link
            to="/api-keys"
            class="flex items-center p-3 rounded-lg hover:bg-gray-50 transition-colors"
          >
            <div class="w-10 h-10 rounded-full bg-green-100 flex items-center justify-center mr-3">
              <svg class="w-6 h-6 text-green-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 7a2 2 0 012 2m4 0a6 6 0 01-7.743 5.743L11 17H9v2H7v2H4a1 1 0 01-1-1v-2.586a1 1 0 01.293-.707l5.964-5.964A6 6 0 1121 9z" />
              </svg>
            </div>
            <div>
              <p class="text-sm font-medium text-gray-900">管理API密钥</p>
              <p class="text-xs text-gray-500">创建和管理API访问密钥</p>
            </div>
          </router-link>

          <router-link
            to="/experiments"
            class="flex items-center p-3 rounded-lg hover:bg-gray-50 transition-colors"
          >
            <div class="w-10 h-10 rounded-full bg-blue-100 flex items-center justify-center mr-3">
              <svg class="w-6 h-6 text-blue-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19.428 15.428a2 2 0 00-1.022-.547l-2.387-.477a6 6 0 00-3.86.517l-.318.158a6 6 0 01-3.86.517L6.05 15.21a2 2 0 00-1.806.547M8 4h8l-1 1v5.172a2 2 0 00.586 1.414l5 5c1.26 1.26.367 3.414-1.415 3.414H4.828c-1.782 0-2.674-2.154-1.414-3.414l5-5A2 2 0 009 10.172V5L8 4z" />
              </svg>
            </div>
            <div>
              <p class="text-sm font-medium text-gray-900">创建实验</p>
              <p class="text-xs text-gray-500">设置A/B测试实验</p>
            </div>
          </router-link>
        </div>
      </div>

      <!-- 最近活动 -->
      <div class="card">
        <h3 class="text-lg font-medium text-gray-900 mb-4">最近活动</h3>
        <div v-if="loading" class="text-center py-4">
          <div class="animate-spin rounded-full h-8 w-8 border-b-2 border-primary-600 mx-auto"></div>
        </div>
        <div v-else-if="recentActivities.length === 0" class="text-center py-4 text-gray-500">
          暂无活动记录
        </div>
        <div v-else class="space-y-3">
          <div
            v-for="activity in recentActivities"
            :key="activity.id"
            class="flex items-start p-3 rounded-lg hover:bg-gray-50"
          >
            <div class="flex-shrink-0">
              <div class="w-8 h-8 rounded-full bg-gray-100 flex items-center justify-center">
                <span class="text-xs font-medium text-gray-600">
                  {{ activity.username?.charAt(0)?.toUpperCase() || 'U' }}
                </span>
              </div>
            </div>
            <div class="ml-3 flex-1">
              <p class="text-sm text-gray-900">
                <span class="font-medium">{{ activity.username }}</span>
                <span :class="getActionColor(activity.action)" class="mx-1">
                  {{ activity.action }}
                </span>
                <span class="text-gray-500">{{ activity.resourceType }}</span>
              </p>
              <p class="text-xs text-gray-500 mt-1">
                {{ formatDate(activity.createdAt) }}
              </p>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>