import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { settle } from '../helpers/settle.js'

/**
 * views/DimensionSyncView.vue：上半区同步状态（既有观测面）+ 下半区 HTTP Pull 源配置——
 * - 初载：sync-status 行 + Pull 配置行（源标识/环境/维度/endpoint/启用徽章/游标/累计推送）与空态
 * - 创建弹层：必填校验（环境/源标识/endpoint/凭证）、POST payload（数值字段转数字、凭证提交）
 * - 编辑弹层：预填、PUT 到配置 id、credential 留空不提交该字段（后端保留既有凭证）
 * - 行操作：立即拉取（成功/失败两口径出结果文案）、删除（confirm 拦截 + DELETE）
 * - YAML 面：导出弹窗取文本、导入本地拦截空内容/POST 文本体/成功计数文案/服务端 400 透出
 *
 * useGameList 模块单例——每用例 resetModules + 先设 localStorage 再动态 import 视图；
 * GameSelector 打桩隔离（与 RiskLabView.spec 同口径）。
 */
vi.mock('@/services/api', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }
}))

const STORAGE_KEY = 'oddsmaker.selectedGameId'
const BASE = '/api/games/g1/dimension-pulls'

const ok = (data) => new Promise((res) => setTimeout(() => res({ data }), 0))
const bad = (message, status = 400) =>
  Promise.reject(Object.assign(new Error(message), { response: { status, data: { message } } }))

const STATUS_ROWS = [
  {
    id: 'dps_1', sourceType: 'agent', sourceKey: 'items_mysql', environment: 'prod',
    cursor: 'mysql-1001', sinceLastPushSeconds: 40, sinceLastEventSeconds: 120,
    pushedCount: 1000, errorCount: 0, lastError: null, lastPushAt: '2026-10-11T02:10:00'
  }
]

const PULLS = [
  {
    id: 'dpc_1', sourceKey: 'items', environment: 'prod', dimType: 'item',
    endpoint: 'https://api.game-x.com/v1/items', enabled: true,
    intervalSeconds: 300, pageLimit: 1000, lastCursor: '1760000000001',
    lastPullAt: '2026-10-11T02:11:00', pushedCount: 12, errorCount: 0, lastError: null
  },
  {
    id: 'dpc_2', sourceKey: 'levels', environment: 'prod', dimType: 'level',
    endpoint: 'https://api.game-x.com/v1/levels', enabled: false,
    intervalSeconds: 600, pageLimit: 500, lastCursor: null,
    lastPullAt: null, pushedCount: 0, errorCount: 1, lastError: '游戏 API 返回 HTTP 503'
  }
]

const ENVIRONMENTS = [{ id: 'e1', name: 'prod' }, { id: 'e2', name: 'staging' }]

const YAML_TEXT = ['# 导出注释', 'dimensions:', '  items:', '    environment: prod', '    endpoint: https://api.game-x.com/v1/items'].join('\n')

async function fresh({ statusRows = STATUS_ROWS, pulls = PULLS, environments = ENVIRONMENTS } = {}) {
  vi.resetModules()
  localStorage.clear()
  localStorage.setItem(STORAGE_KEY, 'g1')

  const api = (await import('@/services/api')).default
  api.get.mockImplementation((url) => {
    if (url === '/api/dimensions/sync-status') return ok(statusRows)
    if (url === BASE) return ok(pulls)
    if (url === '/api/games/g1/environments') return ok(environments)
    if (url === `${BASE}/export`) return ok(YAML_TEXT)
    return ok([])
  })
  api.post.mockResolvedValue(ok({ success: true, pushed: 3, cursor: 'c2', error: null }))
  api.put.mockResolvedValue(ok(pulls[0]))
  api.delete.mockResolvedValue(ok({ deleted: true }))

  const DimensionSyncView = (await import('@/views/DimensionSyncView.vue')).default
  const wrapper = mount(DimensionSyncView, { global: { stubs: { GameSelector: true } } })
  await settle()
  return { wrapper, api }
}

/** 弹层内按 DOM 顺序取 input.input：环境/源标识/间隔/endpoint/凭证/分页 */
const formInputs = (w) => w.findAll('input.input')

const button = (w, text) => w.findAll('button').find(b => b.text() === text)

describe('DimensionSyncView', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
  })

  it('初载：同步状态行与 Pull 配置行同页渲染', async () => {
    const { wrapper } = await fresh()
    expect(wrapper.text()).toContain('维度同步')
    // 状态区（Agent 心跳观测）
    expect(wrapper.text()).toContain('items_mysql')
    expect(wrapper.text()).toContain('心跳正常')
    // Pull 配置区
    expect(wrapper.text()).toContain('HTTP Pull 源配置（2）')
    expect(wrapper.text()).toContain('items')
    expect(wrapper.text()).toContain('https://api.game-x.com/v1/items')
    expect(wrapper.text()).toContain('启用')
    expect(wrapper.text()).toContain('停用')
    expect(wrapper.text()).toContain('300s / 1000')
    expect(wrapper.text()).toContain('失败 1：游戏 API 返回 HTTP 503')
    expect(wrapper.text()).toContain('1760000000001')
  })

  it('初载：无 Pull 配置出接入引导空态', async () => {
    const { wrapper } = await fresh({ pulls: [] })
    expect(wrapper.text()).toContain('HTTP Pull 源配置（0）')
    expect(wrapper.text()).toContain('暂无 HTTP Pull 配置')
  })

  it('创建：必填校验拦截，合法提交 POST 正确 payload 并提示', async () => {
    const { wrapper, api } = await fresh({ pulls: [] })
    api.post.mockResolvedValue(ok({ id: 'dpc_new', sourceKey: 'skills' }))
    api.get.mockImplementation((url) => {
      if (url === BASE) return ok([{ id: 'dpc_new', sourceKey: 'skills', environment: 'prod' }])
      if (url === '/api/games/g1/environments') return ok(ENVIRONMENTS)
      if (url === '/api/dimensions/sync-status') return ok([])
      return ok([])
    })

    await button(wrapper, '新建 Pull 源').trigger('click')
    await settle()
    expect(wrapper.text()).toContain('新建 HTTP Pull 源')

    // 环境缺失（手动清空环境）→ 校验
    await formInputs(wrapper)[0].setValue('')
    await button(wrapper, '创建').trigger('click')
    await settle()
    expect(wrapper.text()).toContain('请填写环境')
    expect(api.post).not.toHaveBeenCalled()

    // 源标识缺失
    await formInputs(wrapper)[0].setValue('prod')
    await button(wrapper, '创建').trigger('click')
    await settle()
    expect(wrapper.text()).toContain('请填写源标识')
    expect(api.post).not.toHaveBeenCalled()

    // endpoint 缺失
    await formInputs(wrapper)[1].setValue('skills')
    await button(wrapper, '创建').trigger('click')
    await settle()
    expect(wrapper.text()).toContain('请填写游戏方 API 地址')
    expect(api.post).not.toHaveBeenCalled()

    // credential 缺失（建档必填）
    await formInputs(wrapper)[3].setValue('https://api.game-x.com/v1/skills')
    await button(wrapper, '创建').trigger('click')
    await settle()
    expect(wrapper.text()).toContain('请填写 Bearer 凭证')
    expect(api.post).not.toHaveBeenCalled()

    await formInputs(wrapper)[2].setValue('60')
    await formInputs(wrapper)[4].setValue('bearer-token-1')
    await button(wrapper, '创建').trigger('click')
    await settle()

    expect(api.post).toHaveBeenCalledWith(BASE, {
      environment: 'prod', sourceKey: 'skills', dimType: 'item',
      endpoint: 'https://api.game-x.com/v1/skills', intervalSeconds: 60,
      pageLimit: 1000, enabled: true, credential: 'bearer-token-1'
    })
    expect(wrapper.text()).toContain('已建档「skills」（prod）')
    expect(wrapper.text()).toContain('HTTP Pull 源配置（1）')
  })

  it('编辑：预填现有值，credential 留空不提交该字段（后端保留既有凭证）', async () => {
    const { wrapper, api } = await fresh()
    await button(wrapper, '编辑').trigger('click')
    await settle()

    const inputs = formInputs(wrapper)
    expect(wrapper.text()).toContain('编辑：items（prod）')
    expect(inputs[0].element.value).toBe('prod')
    expect(inputs[1].element.value).toBe('items')
    expect(inputs[3].element.value).toBe('https://api.game-x.com/v1/items')
    expect(inputs[2].element.value).toBe('300')
    expect(inputs[4].element.value).toBe('')   // 凭证不预填，留空=保留

    await button(wrapper, '保存').trigger('click')
    await settle()

    expect(api.put).toHaveBeenCalledTimes(1)
    const [url, payload] = api.put.mock.calls[0]
    expect(url).toBe(`${BASE}/dpc_1`)
    expect(payload).toMatchObject({
      environment: 'prod', sourceKey: 'items', dimType: 'item',
      endpoint: 'https://api.game-x.com/v1/items', intervalSeconds: 300,
      pageLimit: 1000, enabled: true
    })
    expect(payload.credential).toBeUndefined()   // 留空 = 保留既有凭证
    expect(wrapper.text()).toContain('已更新「items」')
  })

  it('编辑：填写新凭证时提交 credential（凭证可轮换）', async () => {
    const { wrapper, api } = await fresh()
    await button(wrapper, '编辑').trigger('click')
    await settle()
    await formInputs(wrapper)[4].setValue('bearer-token-2')
    await button(wrapper, '保存').trigger('click')
    await settle()
    expect(api.put.mock.calls[0][1].credential).toBe('bearer-token-2')
  })

  it('立即拉取：成功文案含推送条数与游标', async () => {
    const { wrapper } = await fresh()
    await button(wrapper, '立即拉取').trigger('click')
    await settle()
    expect(wrapper.text()).toContain('「items」本轮已推 3 条，游标 c2')
  })

  it('立即拉取：失败臂透出后端错误文案', async () => {
    const { wrapper, api } = await fresh()
    api.post.mockResolvedValue(ok({ success: false, pushed: 0, cursor: null, error: '游戏 API 返回 HTTP 503' }))
    await button(wrapper, '立即拉取').trigger('click')
    await settle()
    expect(wrapper.text()).toContain('「items」本轮失败：游戏 API 返回 HTTP 503')
  })

  it('删除：confirm 拦截与确认后 DELETE', async () => {
    const { wrapper, api } = await fresh()

    vi.spyOn(window, 'confirm').mockReturnValue(false)
    await button(wrapper, '删除').trigger('click')
    await settle()
    expect(api.delete).not.toHaveBeenCalled()

    vi.spyOn(window, 'confirm').mockReturnValue(true)
    await button(wrapper, '删除').trigger('click')
    await settle()
    expect(api.delete).toHaveBeenCalledWith(`${BASE}/dpc_1`)
    expect(wrapper.text()).toContain('已删除「items」')
  })

  it('导出 YAML：打开弹窗取 export 文本展示（凭证不在导出物中）', async () => {
    const { wrapper, api } = await fresh()
    await button(wrapper, '导出 YAML').trigger('click')
    await settle()

    expect(api.get).toHaveBeenCalledWith(`${BASE}/export`)
    expect(wrapper.text()).toContain('导出 Pull 配置 YAML')
    expect(wrapper.find('textarea').element.value).toContain('dimensions:')
    expect(wrapper.find('textarea').element.value).toContain('https://api.game-x.com/v1/items')
    expect(wrapper.find('textarea').element.value).not.toContain('credential:')
  })

  it('导入 YAML：空内容本地拦截，非法 YAML 透出服务端错误', async () => {
    const { wrapper, api } = await fresh()
    await button(wrapper, '导入 YAML').trigger('click')
    await settle()

    await button(wrapper, '导入').trigger('click')
    await settle()
    expect(api.post).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请粘贴 YAML 内容')

    await wrapper.find('textarea').setValue('dimensions: {}')
    api.post.mockResolvedValueOnce(bad('YAML 中没有可导入的 dimension 配置'))
    await button(wrapper, '导入').trigger('click')
    await settle()
    expect(wrapper.text()).toContain('YAML 中没有可导入的 dimension 配置')
  })

  it('导入 YAML：POST 文本体并回显新建/更新计数', async () => {
    const { wrapper, api } = await fresh({ pulls: [] })
    api.post.mockResolvedValueOnce(ok({ created: 1, updated: 0, total: 1 }))
    api.get.mockImplementation((url) => {
      if (url === BASE) return ok([{ id: 'dpc_new', sourceKey: 'items', environment: 'prod' }])
      return ok([])
    })

    await button(wrapper, '导入 YAML').trigger('click')
    await settle()
    const yaml = 'dimensions:\n  items:\n    environment: prod\n    endpoint: https://api.game-x.com/v1/items\n    credential: bearer-token-1'
    await wrapper.find('textarea').setValue(yaml)
    await button(wrapper, '导入').trigger('click')
    await settle()

    expect(api.post).toHaveBeenCalledWith(`${BASE}/import`, yaml)
    expect(wrapper.text()).toContain('已导入 1 条：新建 1、更新 0')
    expect(wrapper.text()).toContain('HTTP Pull 源配置（1）')
  })
})
