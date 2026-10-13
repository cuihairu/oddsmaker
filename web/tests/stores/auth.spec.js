import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

/**
 * stores/auth.js 登录态契约：
 * - token 初始化自 localStorage（刷新后仍处于登录态的根保证），user 只存内存
 * - login 成功写 token/user/localStorage/Authorization 头，失败落 error 且不动登录态
 * - logout / fetchUser 失败都把认证状态清干净（含 Authorization 头）
 * - isAdmin / userName 的可选链兜底与回退顺序
 */
vi.mock('@/services/api', () => {
  const api = {
    post: vi.fn(),
    get: vi.fn(),
    defaults: { headers: { common: {} } }
  }
  return { default: api }
})

import api from '@/services/api'
import { useAuthStore } from '@/stores/auth'

beforeEach(() => {
  localStorage.clear()
  vi.clearAllMocks()
  setActivePinia(createPinia())
})

describe('登录态初始化与持久化', () => {
  it('localStorage 无 token：初始为未登录态', () => {
    const auth = useAuthStore()
    expect(auth.token).toBeNull()
    expect(auth.user).toBeNull()
    expect(auth.isAuthenticated).toBe(false)
  })

  it('localStorage 有 token：初始即为已登录态（user 仍为空，需 fetchUser 补齐）', () => {
    localStorage.setItem('token', 'stored-tok')
    const auth = useAuthStore()
    expect(auth.token).toBe('stored-tok')
    expect(auth.isAuthenticated).toBe(true)
    expect(auth.user).toBeNull()
  })
})

describe('login', () => {
  it('成功：写 token/user/localStorage/Authorization 头，返回 true，loading 复位且 error 为空', async () => {
    const auth = useAuthStore()
    api.post.mockResolvedValueOnce({
      data: { token: 'tok-1', user: { username: 'alice', displayName: 'Alice', roles: ['ADMIN'] } }
    })
    const ok = await auth.login('alice', 'pw')
    expect(ok).toBe(true)
    expect(api.post).toHaveBeenCalledWith('/api/auth/login', { username: 'alice', password: 'pw' })
    expect(auth.token).toBe('tok-1')
    expect(auth.user).toEqual({ username: 'alice', displayName: 'Alice', roles: ['ADMIN'] })
    expect(localStorage.getItem('token')).toBe('tok-1')
    expect(api.defaults.headers.common['Authorization']).toBe('Bearer tok-1')
    expect(auth.loading).toBe(false)
    expect(auth.error).toBeNull()
  })

  it('失败（后端 message）：error 落后端消息、返回 false、登录态与持久化不受影响', async () => {
    localStorage.setItem('token', 'old-tok')
    const auth = useAuthStore()
    api.post.mockRejectedValueOnce({ response: { data: { message: '账号或密码错误' } } })
    const ok = await auth.login('alice', 'wrong')
    expect(ok).toBe(false)
    expect(auth.error).toBe('账号或密码错误')
    expect(auth.loading).toBe(false)
    // 失败不触碰既有 token 态
    expect(auth.token).toBe('old-tok')
    expect(localStorage.getItem('token')).toBe('old-tok')
  })

  it('失败（网络错误无 response）：error 落默认文案「登录失败」', async () => {
    const auth = useAuthStore()
    api.post.mockRejectedValueOnce(new Error('Network Error'))
    const ok = await auth.login('alice', 'pw')
    expect(ok).toBe(false)
    expect(auth.error).toBe('登录失败')
  })

  it('MFA 挑战（后端 {mfaRequired:true}）：返回 false、mfaRequired 置位、不碰 token/持久化/error', async () => {
    localStorage.setItem('token', 'old-tok')
    const auth = useAuthStore()
    api.post.mockResolvedValueOnce({ data: { mfaRequired: true, message: '需要 MFA 验证码' } })
    const ok = await auth.login('alice', 'pw')
    expect(ok).toBe(false)
    expect(auth.mfaRequired).toBe(true)
    // 挑战不是错误：error 保持空，既有登录态不动
    expect(auth.error).toBeNull()
    expect(auth.token).toBe('old-tok')
    expect(localStorage.getItem('token')).toBe('old-tok')
  })

  it('MFA 验证码透传：login 第三参 mfaCode 进请求体，无 mfaCode 时请求体不携带该键', async () => {
    const auth = useAuthStore()
    api.post.mockResolvedValueOnce({ data: { token: 'tok-1', user: { username: 'alice' } } })
    await auth.login('alice', 'pw', '123456')
    expect(api.post).toHaveBeenCalledWith('/api/auth/login',
      { username: 'alice', password: 'pw', mfaCode: '123456' })

    api.post.mockResolvedValueOnce({ data: { token: 'tok-2', user: { username: 'alice' } } })
    await auth.login('alice', 'pw')
    expect(api.post).toHaveBeenLastCalledWith('/api/auth/login',
      { username: 'alice', password: 'pw' })
  })

  it('MFA 两步流：先挑战后带码成功——mfaRequired 置位再复位，token 只在第二步落盘', async () => {
    const auth = useAuthStore()
    api.post.mockResolvedValueOnce({ data: { mfaRequired: true } })
    const step1 = await auth.login('alice', 'pw')
    expect(step1).toBe(false)
    expect(auth.mfaRequired).toBe(true)
    expect(auth.token).toBeNull()

    api.post.mockResolvedValueOnce({ data: { token: 'tok-mfa', user: { username: 'alice', roles: ['ADMIN'] } } })
    const step2 = await auth.login('alice', 'pw', '654321')
    expect(step2).toBe(true)
    expect(auth.mfaRequired).toBe(false)
    expect(auth.token).toBe('tok-mfa')
    expect(localStorage.getItem('token')).toBe('tok-mfa')
  })

  it('mfaRequired 每次登录先复位：上一次挑战的残位不污染新请求的响应分支', async () => {
    const auth = useAuthStore()
    api.post.mockResolvedValueOnce({ data: { mfaRequired: true } })
    await auth.login('alice', 'pw')
    expect(auth.mfaRequired).toBe(true)

    // 新登录（如换了用户）直接成功：mfaRequired 应在请求前被复位为 false
    api.post.mockResolvedValueOnce({ data: { token: 'tok-x', user: { username: 'bob' } } })
    await auth.login('bob', 'pw')
    expect(auth.mfaRequired).toBe(false)
  })
})

describe('logout', () => {
  it('成功路径：清空 token/user/localStorage/Authorization 头', async () => {
    localStorage.setItem('token', 'tok-1')
    const auth = useAuthStore()
    api.post.mockResolvedValueOnce({ data: {} })
    await auth.logout()
    expect(api.post).toHaveBeenCalledWith('/api/auth/logout')
    expect(auth.token).toBeNull()
    expect(auth.user).toBeNull()
    expect(auth.isAuthenticated).toBe(false)
    expect(localStorage.getItem('token')).toBeNull()
    expect('Authorization' in api.defaults.headers.common).toBe(false)
  })

  it('logout 请求失败也被忽略：认证状态照样清空（忽略登出错误）', async () => {
    localStorage.setItem('token', 'tok-1')
    const auth = useAuthStore()
    api.post.mockRejectedValueOnce(new Error('Network Error'))
    await auth.logout()
    expect(auth.token).toBeNull()
    expect(localStorage.getItem('token')).toBeNull()
    expect('Authorization' in api.defaults.headers.common).toBe(false)
  })
})

describe('fetchUser / init', () => {
  it('无 token：不发请求（early return）', async () => {
    const auth = useAuthStore()
    await auth.fetchUser()
    expect(api.get).not.toHaveBeenCalled()
  })

  it('成功：带 token 拉 /api/users/me 填充 user，并设置 Authorization 头', async () => {
    localStorage.setItem('token', 'tok-1')
    const auth = useAuthStore()
    api.get.mockResolvedValueOnce({ data: { username: 'bob', roles: ['USER'] } })
    await auth.fetchUser()
    expect(api.get).toHaveBeenCalledWith('/api/users/me')
    expect(auth.user).toEqual({ username: 'bob', roles: ['USER'] })
    expect(api.defaults.headers.common['Authorization']).toBe('Bearer tok-1')
  })

  it('失败（token 无效）：清空 token/user/localStorage/Authorization 头', async () => {
    localStorage.setItem('token', 'expired-tok')
    const auth = useAuthStore()
    api.get.mockRejectedValueOnce({ response: { status: 401 } })
    await auth.fetchUser()
    expect(auth.token).toBeNull()
    expect(auth.user).toBeNull()
    expect(auth.isAuthenticated).toBe(false)
    expect(localStorage.getItem('token')).toBeNull()
    expect('Authorization' in api.defaults.headers.common).toBe(false)
  })

  it('init：有 token 才触发 fetchUser', async () => {
    const auth = useAuthStore()
    auth.init()
    await Promise.resolve()
    expect(api.get).not.toHaveBeenCalled()

    localStorage.setItem('token', 'tok-1')
    // 新 pinia 实例模拟「刷新后 init」
    setActivePinia(createPinia())
    const auth2 = useAuthStore()
    api.get.mockResolvedValueOnce({ data: { username: 'bob' } })
    auth2.init()
    await vi.waitFor(() => expect(api.get).toHaveBeenCalledWith('/api/users/me'))
  })
})

describe('computed 派生', () => {
  it('isAdmin：roles 含 ADMIN 才为 true；user/roles 缺失走可选链兜底 false', () => {
    const auth = useAuthStore()
    auth.user = { roles: ['USER'] }
    expect(auth.isAdmin).toBe(false)
    auth.user = { roles: ['USER', 'ADMIN'] }
    expect(auth.isAdmin).toBe(true)
    auth.user = {}
    expect(auth.isAdmin).toBe(false)
    auth.user = null
    expect(auth.isAdmin).toBe(false)
  })

  it('userName：displayName 优先，回退 username，两者皆无为空串', () => {
    const auth = useAuthStore()
    expect(auth.userName).toBe('')
    auth.user = { username: 'carol' }
    expect(auth.userName).toBe('carol')
    auth.user = { username: 'carol', displayName: 'Carol C' }
    expect(auth.userName).toBe('Carol C')
  })
})
