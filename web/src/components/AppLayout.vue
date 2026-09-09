<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const authStore = useAuthStore()
const sidebarOpen = ref(false)

const navigation = [
  { name: '仪表盘', href: '/', icon: 'home' },
  { name: '游戏管理', href: '/games', icon: 'gamepad' },
  { name: 'API密钥', href: '/api-keys', icon: 'key' },
  { name: '实验管理', href: '/experiments', icon: 'flask' },
  { name: '风控规则', href: '/risk-rules', icon: 'shield' },
  { name: '留存趋势', href: '/analytics/retention', icon: 'trend' },
  { name: '付费漏斗', href: '/analytics/payment-funnel', icon: 'funnel' },
  { name: '在线监控', href: '/analytics/online', icon: 'bolt' },
  { name: '财务报表', href: '/analytics/finance', icon: 'dollar' },
  { name: '监控', href: '/monitoring', icon: 'chart' },
]

const adminNavigation = [
  { name: '用户管理', href: '/users', icon: 'users' },
  { name: '审计日志', href: '/audit-logs', icon: 'document' },
]

const iconPaths = {
  home: 'M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6',
  gamepad: 'M11 4a2 2 0 114 0v1a1 1 0 001 1h3a1 1 0 011 1v3a1 1 0 01-1 1h-1a2 2 0 100 4h1a1 1 0 011 1v3a1 1 0 01-1 1h-3a1 1 0 01-1-1v-1a2 2 0 10-4 0v1a1 1 0 01-1 1H7a1 1 0 01-1-1v-3a1 1 0 00-1-1H4a2 2 0 110-4h1a1 1 0 001-1V7a1 1 0 011-1h3a1 1 0 001-1V4z',
  key: 'M15 7a2 2 0 012 2m4 0a6 6 0 01-7.743 5.743L11 17H9v2H7v2H4a1 1 0 01-1-1v-2.586a1 1 0 01.293-.707l5.964-5.964A6 6 0 1121 9z',
  flask: 'M19.428 15.428a2 2 0 00-1.022-.547l-2.387-.477a6 6 0 00-3.86.517l-.318.158a6 6 0 01-3.86.517L6.05 15.21a2 2 0 00-1.806.547M8 4h8l-1 1v5.172a2 2 0 00.586 1.414l5 5c1.26 1.26.367 3.414-1.415 3.414H4.828c-1.782 0-2.674-2.154-1.414-3.414l5-5A2 2 0 009 10.172V5L8 4z',
  shield: 'M9 12l2 2 4-4m5.618-4.016A11.955 11.955 0 0112 2.944a11.955 11.955 0 01-8.618 3.04A12.02 12.02 0 003 9c0 5.591 3.824 10.29 9 11.622 5.176-1.332 9-6.03 9-11.622 0-1.042-.133-2.052-.382-3.016z',
  chart: 'M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z',
  users: 'M12 4.354a4 4 0 110 5.292M15 21H3v-1a6 6 0 0112 0v1zm0 0h6v-1a6 6 0 00-9-5.197M13 7a4 4 0 11-8 0 4 4 0 018 0z',
  document: 'M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z',
  trend: 'M3 17l6-6 4 4 8-8M14 7h7v7',
  funnel: 'M3 4h18l-7 8v5l-4 3v-8L3 4z',
  bolt: 'M13 10V3L4 14h7v7l9-11h-7z',
  dollar: 'M12 2v20m5-17H9.5a3.5 3.5 0 000 7h5a3.5 3.5 0 010 7H6',
}

function toggleSidebar() {
  sidebarOpen.value = !sidebarOpen.value
}

async function handleLogout() {
  await authStore.logout()
  router.push('/login')
}
</script>

<template>
  <div class="min-h-screen bg-gray-50">
    <!-- 移动端侧边栏遮罩 -->
    <div
      v-if="sidebarOpen"
      class="fixed inset-0 z-40 lg:hidden"
      @click="sidebarOpen = false"
    >
      <div class="fixed inset-0 bg-gray-600 bg-opacity-75"></div>
    </div>

    <!-- 侧边栏 -->
    <div
      :class="[
        'fixed inset-y-0 left-0 z-50 w-64 bg-white shadow-lg transform transition-transform duration-300 ease-in-out lg:translate-x-0 lg:static lg:inset-auto',
        sidebarOpen ? 'translate-x-0' : '-translate-x-full'
      ]"
    >
      <div class="flex flex-col h-full">
        <!-- Logo -->
        <div class="flex items-center h-16 px-6 border-b border-gray-200">
          <h1 class="text-xl font-bold text-primary-600">Oddsmaker</h1>
        </div>

        <!-- 导航菜单 -->
        <nav class="flex-1 px-4 py-6 space-y-1 overflow-y-auto">
          <router-link
            v-for="item in navigation"
            :key="item.name"
            :to="item.href"
            class="flex items-center px-3 py-2 text-sm font-medium text-gray-700 rounded-lg hover:bg-gray-100"
            :class="{ 'bg-primary-50 text-primary-700': $route.path === item.href }"
            @click="sidebarOpen = false"
          >
            <svg
              class="w-5 h-5 mr-3 text-gray-400"
              :class="{ 'text-primary-500': $route.path === item.href }"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="2"
                :d="iconPaths[item.icon]"
              />
            </svg>
            {{ item.name }}
          </router-link>

          <!-- 管理员菜单 -->
          <template v-if="authStore.isAdmin">
            <div class="pt-4 mt-4 border-t border-gray-200">
              <p class="px-3 text-xs font-semibold text-gray-400 uppercase tracking-wider">
                管理
              </p>
            </div>
            <router-link
              v-for="item in adminNavigation"
              :key="item.name"
              :to="item.href"
              class="flex items-center px-3 py-2 text-sm font-medium text-gray-700 rounded-lg hover:bg-gray-100"
              :class="{ 'bg-primary-50 text-primary-700': $route.path === item.href }"
              @click="sidebarOpen = false"
            >
              <svg
                class="w-5 h-5 mr-3 text-gray-400"
                :class="{ 'text-primary-500': $route.path === item.href }"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  :d="iconPaths[item.icon]"
                />
              </svg>
              {{ item.name }}
            </router-link>
          </template>
        </nav>

        <!-- 用户信息 -->
        <div class="px-4 py-4 border-t border-gray-200">
          <div class="flex items-center">
            <div class="flex-shrink-0">
              <div class="w-8 h-8 rounded-full bg-primary-100 flex items-center justify-center">
                <span class="text-sm font-medium text-primary-600">
                  {{ authStore.userName?.charAt(0)?.toUpperCase() || 'U' }}
                </span>
              </div>
            </div>
            <div class="ml-3">
              <p class="text-sm font-medium text-gray-700">{{ authStore.userName }}</p>
              <p class="text-xs text-gray-500">
                {{ authStore.isAdmin ? '管理员' : '用户' }}
              </p>
            </div>
            <button
              @click="handleLogout"
              class="ml-auto p-1 text-gray-400 hover:text-gray-600"
              title="登出"
            >
              <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1"
                />
              </svg>
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- 主内容区 -->
    <div class="lg:pl-64">
      <!-- 顶部导航栏 -->
      <div class="sticky top-0 z-10 flex items-center h-16 px-4 bg-white border-b border-gray-200 lg:px-8">
        <button
          @click="toggleSidebar"
          class="p-2 text-gray-400 rounded-lg lg:hidden hover:text-gray-600 hover:bg-gray-100"
        >
          <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path
              stroke-linecap="round"
              stroke-linejoin="round"
              stroke-width="2"
              d="M4 6h16M4 12h16M4 18h16"
            />
          </svg>
        </button>
        
        <div class="flex-1"></div>
        
        <div class="flex items-center space-x-4">
          <!-- 通知按钮 -->
          <button class="p-2 text-gray-400 rounded-lg hover:text-gray-600 hover:bg-gray-100">
            <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="2"
                d="M15 17h5l-1.405-1.405A2.032 2.032 0 0118 14.158V11a6.002 6.002 0 00-4-5.659V5a2 2 0 10-4 0v.341C7.67 6.165 6 8.388 6 11v3.159c0 .538-.214 1.055-.595 1.436L4 17h5m6 0v1a3 3 0 11-6 0v-1m6 0H9"
              />
            </svg>
          </button>
        </div>
      </div>

      <!-- 页面内容 -->
      <main class="p-4 lg:p-8">
        <slot />
      </main>
    </div>
  </div>
</template>