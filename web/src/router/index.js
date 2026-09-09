import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { requiresAuth: false }
    },
    {
      path: '/',
      name: 'dashboard',
      component: () => import('@/views/DashboardView.vue'),
      meta: { requiresAuth: true }
    },
    {
      path: '/games',
      name: 'games',
      component: () => import('@/views/GamesView.vue'),
      meta: { requiresAuth: true }
    },
    {
      path: '/games/:gameId',
      name: 'game-detail',
      component: () => import('@/views/GameDetailView.vue'),
      meta: { requiresAuth: true }
    },
    {
      path: '/api-keys',
      name: 'api-keys',
      component: () => import('@/views/ApiKeysView.vue'),
      meta: { requiresAuth: true }
    },
    {
      path: '/experiments',
      name: 'experiments',
      component: () => import('@/views/ExperimentsView.vue'),
      meta: { requiresAuth: true }
    },
    {
      path: '/risk-rules',
      name: 'risk-rules',
      component: () => import('@/views/RiskRulesView.vue'),
      meta: { requiresAuth: true }
    },
    {
      path: '/users',
      name: 'users',
      component: () => import('@/views/UsersView.vue'),
      meta: { requiresAuth: true, requiresAdmin: true }
    },
    {
      path: '/audit-logs',
      name: 'audit-logs',
      component: () => import('@/views/AuditLogsView.vue'),
      meta: { requiresAuth: true, requiresAdmin: true }
    },
    {
      path: '/monitoring',
      name: 'monitoring',
      component: () => import('@/views/MonitoringView.vue'),
      meta: { requiresAuth: true }
    },
    {
      path: '/analytics/retention',
      name: 'retention',
      component: () => import('@/views/RetentionView.vue'),
      meta: { requiresAuth: true }
    },
    {
      path: '/analytics/payment-funnel',
      name: 'payment-funnel',
      component: () => import('@/views/PaymentFunnelView.vue'),
      meta: { requiresAuth: true }
    },
    {
      path: '/analytics/online',
      name: 'online-monitor',
      component: () => import('@/views/OnlineView.vue'),
      meta: { requiresAuth: true }
    },
    {
      path: '/analytics/finance',
      name: 'finance',
      component: () => import('@/views/FinanceView.vue'),
      meta: { requiresAuth: true }
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('@/views/NotFoundView.vue')
    }
  ]
})

router.beforeEach((to, from, next) => {
  const authStore = useAuthStore()
  
  if (to.meta.requiresAuth && !authStore.isAuthenticated) {
    next({ name: 'login' })
  } else if (to.meta.requiresAdmin && !authStore.isAdmin) {
    next({ name: 'dashboard' })
  } else {
    next()
  }
})

export default router