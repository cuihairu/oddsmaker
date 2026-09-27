import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { AxiosError, AxiosHeaders } from 'axios'
import api from '@/services/api'

/**
 * services/api.js 的请求封装契约。测试走真实 axios 管线，仅在管线末端注入自定义
 * adapter 截获请求 / 伪造响应：请求拦截器（token 注入）在 adapter 之前生效，响应拦截器
 * （401 处理）在 axios 依 validateStatus 判定失败之后生效——因此拦截器的注册与执行顺序
 * 也在被测范围内，而不是只调 handler 数组内部函数。
 *
 * 已知 jsdom 限制（如实记账）：401 分支里的 `window.location.href = '/login'` 在 jsdom
 * 下触发 "Not implemented: navigation"，赋值是 no-op 且 location 不可被 stub（jsdom 的
 * window.location / Location 实例均 non-configurable，实测）。因此该行只验证「执行不抛」，
 * 断言落在可观测副作用上：token 被清除 + 错误继续向外 reject。
 */

function adapterCapture(status = 200, data = {}) {
  const state = { lastConfig: null, calls: 0 }
  const adapter = (config) => {
    state.lastConfig = config
    state.calls += 1
    const response = { data, status, statusText: '', headers: new AxiosHeaders(), config }
    if (status < 200 || status >= 300) {
      // 与真实 adapter 的 settle() 同语义：非 2xx 由 adapter 侧拒绝出带 response 的 AxiosError
      // （validateStatus 判定发生在 xhr/http adapter 内部，不在 axios core）
      return Promise.reject(
        new AxiosError(`Request failed with status code ${status}`, AxiosError.ERR_BAD_REQUEST, config, null, response)
      )
    }
    return Promise.resolve(response)
  }
  return { adapter, state }
}

beforeEach(() => {
  localStorage.clear()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('services/api 实例契约', () => {
  it('实例配置：超时 30s、Content-Type application/json、测试环境无 VITE_API_URL 时 baseURL 为空串', async () => {
    expect(api.defaults.timeout).toBe(30000)
    expect(api.defaults.headers['Content-Type']).toBe('application/json')
    expect(api.defaults.baseURL).toBe('')

    const { adapter, state } = adapterCapture()
    await api.get('/api/ping', { adapter })
    expect(state.calls).toBe(1)
    expect(state.lastConfig.url).toBe('/api/ping')
    expect(state.lastConfig.timeout).toBe(30000)
    expect(state.lastConfig.headers.get('Content-Type')).toBe('application/json')
  })
})

describe('请求拦截器：token 注入', () => {
  it('localStorage 有 token 时注入 Bearer 头', async () => {
    localStorage.setItem('token', 'tok-abc')
    const { adapter, state } = adapterCapture()
    await api.get('/api/games', { adapter })
    expect(state.lastConfig.headers.get('Authorization')).toBe('Bearer tok-abc')
  })

  it('无 token 时不注入 Authorization 头', async () => {
    const { adapter, state } = adapterCapture()
    await api.get('/api/games', { adapter })
    // AxiosHeaders.get 对不存在的键返回 undefined（源码确认：非 null）
    expect(state.lastConfig.headers.get('Authorization')).toBeUndefined()
  })
})

describe('响应拦截器', () => {
  it('2xx 响应原样透传（data 与 adapter 返回一致）', async () => {
    const { adapter } = adapterCapture(200, { ok: 1 })
    const res = await api.get('/api/games', { adapter })
    expect(res.status).toBe(200)
    expect(res.data).toEqual({ ok: 1 })
  })

  it('401：清除 localStorage token 且错误继续 reject（导航赋值在 jsdom 下为 no-op，见文件头注）', async () => {
    localStorage.setItem('token', 'tok-expired')
    const { adapter } = adapterCapture(401, { message: '未登录' })
    // axios 依 validateStatus 判定 401 失败 → 产生带 response 的 AxiosError → 进入错误拦截器
    await expect(api.get('/api/users/me', { adapter })).rejects.toMatchObject({
      response: { status: 401 }
    })
    expect(localStorage.getItem('token')).toBeNull()
  })

  it('非 401 失败（500）：token 保留，错误继续 reject', async () => {
    localStorage.setItem('token', 'tok-keep')
    const { adapter } = adapterCapture(500, { message: '炸了' })
    await expect(api.get('/api/games', { adapter })).rejects.toMatchObject({
      response: { status: 500 }
    })
    expect(localStorage.getItem('token')).toBe('tok-keep')
  })

  it('网络错误（无 response）：token 保留，原错误 reject', async () => {
    localStorage.setItem('token', 'tok-keep')
    const adapter = () => Promise.reject(new Error('Network Error'))
    await expect(api.get('/api/games', { adapter })).rejects.toThrow('Network Error')
    expect(localStorage.getItem('token')).toBe('tok-keep')
  })

  it('请求拦截器错误臂：前置拦截器拒绝时应用侧 handler 原样透传（axios 后注册先执行）', async () => {
    const sentinel = new Error('upstream guard rejected')
    const id = api.interceptors.request.use(
      () => Promise.reject(sentinel),
      (error) => Promise.reject(error)
    )
    try {
      const { adapter, state } = adapterCapture()
      await expect(api.get('/api/games', { adapter })).rejects.toBe(sentinel)
      expect(state.calls).toBe(0)
    } finally {
      api.interceptors.request.eject(id)
    }
  })
})
