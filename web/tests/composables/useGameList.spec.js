import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * composables/useGameList.js：模块级单例的游戏列表状态。
 * - 懒加载 + loading 互斥（并发调用只发一次请求）
 * - 首次加载自动选中第一个游戏并持久化到 localStorage
 * - 已有持久化选择时不覆盖；selectGame 即改即存；空值不持久化
 * - 接口失败 console.error 且不置 loaded（下次仍会重试）
 *
 * 单例状态 → 每个用例 vi.resetModules() 后动态 import 取全新模块与全新 mock。
 */
vi.mock('@/services/api', () => ({ default: { get: vi.fn(), post: vi.fn() } }))

const STORAGE_KEY = 'oddsmaker.selectedGameId'

async function fresh() {
  vi.resetModules()
  const { useGameList } = await import('@/composables/useGameList')
  const api = (await import('@/services/api')).default
  return { useGameList, api }
}

beforeEach(() => {
  localStorage.clear()
})

describe('useGameList', () => {
  it('首次加载：拉列表、自动选中第一个游戏并持久化', async () => {
    const { useGameList, api } = await fresh()
    api.get.mockResolvedValue({ data: { content: [{ id: 'g1' }, { id: 'g2' }] } })

    const ctx = useGameList()
    await vi.waitFor(() => expect(ctx.games.value).toHaveLength(2))
    expect(api.get).toHaveBeenCalledWith('/api/games')
    expect(ctx.currentGameId.value).toBe('g1')
    expect(localStorage.getItem(STORAGE_KEY)).toBe('g1')
    expect(ctx.loading.value).toBe(false)
    expect(api.get).toHaveBeenCalledTimes(1)
    // loaded 已内部置位：再次进入组件不再自动加载
    useGameList()
    await Promise.resolve()
    expect(api.get).toHaveBeenCalledTimes(1)
    // 显式 loadGames() 是幂等重放（不受 loaded 限制）
    await ctx.loadGames()
    expect(api.get).toHaveBeenCalledTimes(2)
    expect(ctx.games.value).toHaveLength(2)
  })

  it('已有持久化选择：初始化即恢复，且加载后不被自动选中覆盖', async () => {
    localStorage.setItem(STORAGE_KEY, 'g2')
    const { useGameList, api } = await fresh()
    api.get.mockResolvedValue({ data: { content: [{ id: 'g1' }, { id: 'g2' }] } })

    const { currentGameId } = useGameList()
    expect(currentGameId.value).toBe('g2')
    await vi.waitFor(() => expect(api.get).toHaveBeenCalledTimes(1))
    expect(currentGameId.value).toBe('g2')
    expect(localStorage.getItem(STORAGE_KEY)).toBe('g2')
  })

  it('selectGame：即改即存；空值不写 localStorage', async () => {
    const { useGameList, api } = await fresh()
    api.get.mockResolvedValue({ data: { content: [{ id: 'g1' }] } })

    const ctx = useGameList()
    await vi.waitFor(() => expect(ctx.games.value).toHaveLength(1))
    ctx.selectGame('g9')
    expect(ctx.currentGameId.value).toBe('g9')
    expect(localStorage.getItem(STORAGE_KEY)).toBe('g9')

    ctx.selectGame('')
    expect(ctx.currentGameId.value).toBe('')
    // persist() 只在有值时写——旧值保留（现状行为，如实钉住）
    expect(localStorage.getItem(STORAGE_KEY)).toBe('g9')
  })

  it('响应缺 content 字段：games 为空数组但算加载完成（再次进入不重发）', async () => {
    const { useGameList, api } = await fresh()
    api.get.mockResolvedValue({ data: {} })

    const ctx = useGameList()
    await vi.waitFor(() => expect(api.get).toHaveBeenCalledTimes(1))
    expect(ctx.games.value).toEqual([])
    useGameList()
    await Promise.resolve()
    expect(api.get).toHaveBeenCalledTimes(1)
  })

  it('接口失败：console.error、列表为空且不置 loaded（下次进入仍会重试）', async () => {
    const errSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const { useGameList, api } = await fresh()
    api.get.mockRejectedValue(new Error('boom'))

    const ctx = useGameList()
    await vi.waitFor(() => expect(errSpy).toHaveBeenCalled())
    expect(ctx.games.value).toEqual([])
    // 未置 loaded → 下一次 useGameList() 再触发加载
    api.get.mockResolvedValue({ data: { content: [{ id: 'g1' }] } })
    const ctx2 = useGameList()
    await vi.waitFor(() => expect(api.get).toHaveBeenCalledTimes(2))
    expect(ctx2.games.value).toHaveLength(1)
    errSpy.mockRestore()
  })

  it('loading 互斥：并发调用 loadGames 只发一次请求', async () => {
    const { useGameList, api } = await fresh()
    api.get.mockReturnValue(new Promise(() => {})) // 永不 resolve——loading 保持 true

    const ctx = useGameList() // 触发第一次 loadGames
    await Promise.resolve()
    const second = ctx.loadGames() // loading 仍为 true → 直接返回
    await Promise.resolve()
    expect(api.get).toHaveBeenCalledTimes(1)
    await second
  })
})
