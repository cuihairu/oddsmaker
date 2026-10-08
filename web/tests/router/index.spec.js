import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

/**
 * router/index.js 的 beforeEach 守卫矩阵：
 *   requiresAuth 未登录 → login；requiresAdmin 非 ADMIN → dashboard；其余放行。
 * api 被 mock 掉（视图懒加载时 useGameList/useSegments 会在模块期发请求），
 * 导航本身走真实 vue-router + jsdom history。
 */
vi.mock('@/services/api', () => {
  const api = {
    get: vi.fn().mockResolvedValue({ data: { content: [] } }),
    post: vi.fn().mockResolvedValue({ data: {} }),
    defaults: { headers: { common: {} } }
  }
  return { default: api }
})

import api from '@/services/api'
import router from '@/router'
import { useAuthStore } from '@/stores/auth'

beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
})

describe('beforeEach 路由守卫', () => {
  it('未登录访问 requiresAuth 路由 → 重定向 login', async () => {
    await router.push('/games')
    expect(router.currentRoute.value.name).toBe('login')
  })

  it('未登录访问 requiresAdmin 路由 → 先被拦到 login（auth 判定在前）', async () => {
    await router.push('/users')
    expect(router.currentRoute.value.name).toBe('login')
  })

  it('已登录但非 ADMIN 访问 admin 路由 → 重定向 dashboard', async () => {
    localStorage.setItem('token', 'tok-user')
    api.get.mockResolvedValueOnce({ data: { username: 'u', roles: ['USER'] } })
    const auth = useAuthStore()
    await auth.fetchUser()
    await router.push('/users')
    expect(router.currentRoute.value.name).toBe('dashboard')
  })

  it('已登录 ADMIN 访问 admin 路由 → 放行', async () => {
    localStorage.setItem('token', 'tok-admin')
    api.get.mockResolvedValueOnce({ data: { username: 'a', roles: ['ADMIN'] } })
    const auth = useAuthStore()
    await auth.fetchUser()
    await router.push('/users')
    expect(router.currentRoute.value.name).toBe('users')
  })

  it('已登录普通用户访问普通受保护路由 → 放行', async () => {
    localStorage.setItem('token', 'tok-user')
    await router.push('/games')
    expect(router.currentRoute.value.name).toBe('games')
  })

  it('catch-all 路由无 meta 守卫：未登录也放行到 not-found', async () => {
    await router.push('/totally-unknown-path')
    expect(router.currentRoute.value.name).toBe('not-found')
  })

  it('登录页本身不要求登录：未登录可直达', async () => {
    await router.push('/login')
    expect(router.currentRoute.value.name).toBe('login')
  })

  // 30s：一次性 await 30 个视图模块的懒加载 chunk（vite 转换 + 解析）。本机 0.4s，
  // 但受限 CPU（CI runner / taskset 2 核实测 5s 默认超时必挂）下转换耗时成倍放大；
  // 这是环境预算而非被测逻辑变慢，故显式放宽而非拆用例掩盖。
  it('路由表完整性：每条记录的懒加载 chunk 都可解析（遍历全部 route arrow 与视图模块顶层）', { timeout: 30000 }, async () => {
    const records = router.getRoutes()
    // 路由表契约：当前 30 个具名路由 + 1 个 not-found catch-all；增删路由须同步此数
    expect(records.length).toBe(31)
    for (const rec of records) {
      // 动态段占位替换后 resolve，取该记录自身的懒加载器执行
      const path = rec.path.replace(/:[^/]+/g, 'seg')
      const matched = router.resolve(path).matched[0]
      const comp = matched && matched.components && matched.components.default
      expect(comp, `路由 ${rec.path} 缺组件`).toBeTruthy()
      // 未导航过的路由是 () => import() 懒加载器；已导航过的被 vue-router 缓存成组件对象
      const mod = typeof comp === 'function' ? await comp() : comp
      expect(mod.default ?? mod, `路由 ${rec.path} chunk 导出异常`).toBeTruthy()
    }
  })
})
