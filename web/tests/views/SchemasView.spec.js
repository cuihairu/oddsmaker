import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { settle, waitFor } from '../helpers/settle.js'

/**
 * views/SchemasView.vue：B7 schemas 资源组页面——
 * - 列表：状态/兼容策略/未知事件拒收徽标、事件数、发布时间、环境（全局回落）
 * - 操作：查看事件展开/收起、兼容检查结果面板（结论/基线/三分类）、
 *   发布成功重载与兼容门拒绝 alert、弃用/删除（confirm 门控、按状态出按钮）
 * - 空态：未选游戏（games 空）与无 Schema
 *
 * 组件依赖 useGameList 模块单例——每用例 resetModules + 先设 localStorage 再动态
 * import 视图（与 SegmentsView.spec.js 同口径）；GameSelector 真实挂载（api 已 mock，
 * 游戏切换流经真实 select 直测）。
 */
vi.mock('@/services/api', () => ({ default: { get: vi.fn(), post: vi.fn(), delete: vi.fn() } }))

const STORAGE_KEY = 'oddsmaker.selectedGameId'

const ok = (data) => new Promise((res) => setTimeout(() => res({ data }), 0))
const bad = (err) => new Promise((_, rej) => setTimeout(() => rej(err), 0))

const GAME = { id: 'g1', name: 'demo', displayName: '演示游戏' }

const SCHEMA_ACTIVE = {
  id: 'sch_a',
  name: 'v1.0',
  version: '1.0.0',
  status: 'ACTIVE',
  environmentId: 'env_prod',
  compatibility: 'BACKWARD',
  rejectUnknownEvents: true,
  totalEvents: 3,
  activeEvents: 2,
  activatedAt: '2026-09-01T00:00:00Z'
}

const SCHEMA_DRAFT = {
  id: 'sch_d',
  name: 'v1.1-draft',
  status: 'DRAFT',
  environmentId: null,
  compatibility: 'FULL',
  rejectUnknownEvents: false,
  totalEvents: 4,
  activeEvents: 4,
  activatedAt: null
}

async function fresh({ selectedGame = 'g1', games = [GAME, { id: 'g2', name: 'demo2' }], schemas = [SCHEMA_ACTIVE, SCHEMA_DRAFT] } = {}) {
  vi.resetModules()
  localStorage.clear()
  if (selectedGame) localStorage.setItem(STORAGE_KEY, selectedGame)

  const api = (await import('@/services/api')).default
  api.get.mockImplementation((url) => {
    if (url === '/api/games') return ok({ content: games })
    if (url === '/api/games/g1/schemas' || url === '/api/games/g2/schemas') return ok({ data: schemas })
    if (url.endsWith('/schemas/sch_d/events')) {
      return ok({ data: [
        { id: 'e1', eventName: 'bet_place', eventType: 'business', importance: 'HIGH', status: 'ACTIVE' },
        { id: 'e2', eventName: 'bet_new', eventType: 'business', importance: 'NORMAL', status: 'ACTIVE' }
      ] })
    }
    if (url.endsWith('/schemas/sch_a/events')) {
      return ok({ data: [
        { id: 'e3', eventName: 'bet_settle', eventType: 'business', importance: 'CRITICAL', status: 'ACTIVE' }
      ] })
    }
    if (url.endsWith('/compatibility')) {
      return ok({ data: {
        schemaId: 'sch_a',
        mode: 'FULL',
        baselineId: 'sch_a',
        compatible: false,
        addedEvents: ['bet_new'],
        removedEvents: ['bet_legacy'],
        changedEvents: []
      } })
    }
    return bad(new Error('unexpected url: ' + url))
  })

  const SchemasView = (await import('@/views/SchemasView.vue')).default
  return { api, SchemasView }
}

async function mountView(SchemasView) {
  const w = mount(SchemasView)
  await settle()
  return w
}

const findBtn = (w, label) => w.findAll('button').find((b) => b.text() === label)
const callsOf = (api, frag) => api.get.mock.calls.filter((c) => c[0].includes(frag))

beforeEach(() => {
  vi.clearAllMocks()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('SchemasView', () => {
  it('加载渲染：名称/环境/状态徽标/兼容策略/未知事件拒收/事件数/发布时间', async () => {
    const { api, SchemasView } = await fresh()
    const w = await mountView(SchemasView)
    const text = w.text()
    expect(text).not.toContain('加载中')
    expect(text).toContain('v1.0')
    expect(text).toContain('env_prod')
    expect(text).toContain('v1.1-draft')
    expect(text).toContain('全局')
    expect(text).toContain('草稿')
    expect(text).toContain('向后兼容')
    expect(text).toContain('完全兼容')
    expect(text).toContain('拒收')
    expect(text).toContain('放行')
    expect(text).toContain('2/3')
    expect(callsOf(api, '/schemas')).toHaveLength(1)
  })

  it('未选游戏（games 为空）：提示先选择游戏且不发 schemas 请求', async () => {
    const { api, SchemasView } = await fresh({ selectedGame: null, games: [] })
    const w = await mountView(SchemasView)
    expect(w.text()).toContain('请选择一个游戏')
    expect(callsOf(api, '/schemas')).toHaveLength(0)
  })

  it('空 Schema 列表：显示空态引导', async () => {
    const { SchemasView } = await fresh({ schemas: [] })
    const w = await mountView(SchemasView)
    expect(w.text()).toContain('暂无事件 Schema')
  })

  it('查看事件：展开所选 Schema 的事件清单，再点收起', async () => {
    const { api, SchemasView } = await fresh()
    const w = await mountView(SchemasView)
    // 第一行 = ACTIVE 的 v1.0
    await findBtn(w, '查看事件').trigger('click')
    await waitFor(() => expect(w.text()).toContain('「v1.0」事件定义（1）'))
    expect(w.text()).toContain('bet_settle')
    expect(api.get).toHaveBeenCalledWith('/api/games/g1/schemas/sch_a/events')

    // 同一 Schema 再点 = 收起
    await findBtn(w, '收起事件').trigger('click')
    await settle()
    expect(w.text()).not.toContain('「v1.0」事件定义（1）')
  })

  it('查看事件：DRAFT 行展开含两条事件', async () => {
    const { api, SchemasView } = await fresh()
    const w = await mountView(SchemasView)
    const draftBtn = w.findAll('button').filter((b) => b.text() === '查看事件')[1]
    await draftBtn.trigger('click')
    await waitFor(() => expect(w.text()).toContain('「v1.1-draft」事件定义（2）'))
    expect(w.text()).toContain('bet_place')
    expect(api.get).toHaveBeenCalledWith('/api/games/g1/schemas/sch_d/events')
  })

  it('兼容检查：渲染结论/基线/新增/下线三分类（FULL 双向违例 → 不兼容）', async () => {
    const { api, SchemasView } = await fresh()
    const w = await mountView(SchemasView)
    await findBtn(w, '兼容检查').trigger('click')
    await waitFor(() => expect(w.text()).toContain('兼容检查结果'))
    expect(w.text()).toContain('不兼容')
    expect(w.text()).toContain('sch_a')
    expect(w.text()).toContain('bet_new')
    expect(w.text()).toContain('下线事件（BACKWARD 违例）：bet_legacy')
    expect(api.get).toHaveBeenCalledWith('/api/games/g1/schemas/sch_a/compatibility')
  })

  it('发布成功：POST publish → 重载列表', async () => {
    const { api, SchemasView } = await fresh()
    api.post.mockImplementation((url) => {
      if (url === '/api/games/g1/schemas/sch_d/publish?userId=system') return ok({ data: {} })
      return bad(new Error('unexpected post: ' + url))
    })
    const w = await mountView(SchemasView)
    await findBtn(w, '发布').trigger('click')
    await settle()
    expect(api.post).toHaveBeenCalledWith('/api/games/g1/schemas/sch_d/publish?userId=system')
    expect(callsOf(api, '/games/g1/schemas').length).toBeGreaterThanOrEqual(2)
  })

  it('发布被兼容门拒绝：alert 透出后端 message 且不重载', async () => {
    const alertSpy = vi.spyOn(window, 'alert').mockImplementation(() => {})
    const { api, SchemasView } = await fresh()
    api.post.mockImplementation((url) => {
      if (url === '/api/games/g1/schemas/sch_d/publish?userId=system') {
        return bad({ response: { data: { message: '不兼容的 Schema 版本发布（mode=BACKWARD...）' } } })
      }
      return bad(new Error('unexpected post: ' + url))
    })
    const w = await mountView(SchemasView)
    await findBtn(w, '发布').trigger('click')
    await settle()
    expect(alertSpy).toHaveBeenCalledWith(expect.stringContaining('不兼容的 Schema 版本发布'))
    expect(callsOf(api, '/games/g1/schemas')).toHaveLength(1)
  })

  it('弃用：confirm 确认后 POST deactivate 并重载', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const { api, SchemasView } = await fresh()
    api.post.mockImplementation((url) => {
      if (url === '/api/games/g1/schemas/sch_a/deactivate') return ok({ data: {} })
      return bad(new Error('unexpected post: ' + url))
    })
    const w = await mountView(SchemasView)
    await findBtn(w, '弃用').trigger('click')
    await settle()
    expect(confirmSpy).toHaveBeenCalled()
    expect(api.post).toHaveBeenCalledWith('/api/games/g1/schemas/sch_a/deactivate')
  })

  it('弃用取消：confirm false 不发请求', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    const { api, SchemasView } = await fresh()
    const w = await mountView(SchemasView)
    await findBtn(w, '弃用').trigger('click')
    await settle()
    expect(api.post).not.toHaveBeenCalled()
  })

  it('删除：仅 DRAFT 行有删除入口，确认后 DELETE 并重载', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const { api, SchemasView } = await fresh()
    api.delete.mockResolvedValue({ data: { data: null } })
    const w = await mountView(SchemasView)
    expect(w.findAll('button').filter((b) => b.text() === '删除')).toHaveLength(1)
    await findBtn(w, '删除').trigger('click')
    await settle()
    expect(confirmSpy).toHaveBeenCalled()
    expect(api.delete).toHaveBeenCalledWith('/api/games/g1/schemas/sch_d')
  })

  it('游戏切换：真实 GameSelector change → 持久化新 id 并按新 id 重新加载', async () => {
    const { api, SchemasView } = await fresh()
    const w = await mountView(SchemasView)
    const selector = w.find('select')
    await selector.setValue('g2')
    expect(localStorage.getItem(STORAGE_KEY)).toBe('g2')
    expect(callsOf(api, '/games/g2/schemas')).toHaveLength(1)
  })

  it('加载失败：console.error 并保持空列表不抛', async () => {
    const errSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const { api, SchemasView } = await fresh()
    api.get.mockImplementation((url) => {
      if (url === '/api/games') return ok({ content: [GAME] })
      return bad(new Error('boom'))
    })
    const w = await mountView(SchemasView)
    expect(errSpy).toHaveBeenCalled()
    expect(w.text()).not.toContain('v1.0')
  })
})
