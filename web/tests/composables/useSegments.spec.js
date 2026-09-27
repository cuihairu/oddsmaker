import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * composables/useSegments.js：模块级单例（依赖 useGameList 的 currentGameId）。
 * - 无游戏 → 早退并清空列表，不发 segments 请求
 * - currentGameId 变化 → watch 重置 segmentId 并重新加载
 * - 只保留 status === 'ACTIVE' 的分群
 * - 接口失败 → console.error + 清空列表
 *
 * 同 useGameList.spec：每用例 resetModules 后动态 import 取全新单例。
 */
vi.mock('@/services/api', () => ({ default: { get: vi.fn(), post: vi.fn() } }))

async function fresh({ games = [{ id: 'g1' }, { id: 'g2' }], segments = [], segmentsError, selectedGame } = {}) {
  vi.resetModules()
  if (selectedGame) localStorage.setItem('oddsmaker.selectedGameId', selectedGame)
  const api = (await import('@/services/api')).default
  api.get.mockImplementation((url) => {
    if (url === '/api/games') return Promise.resolve({ data: { content: games } })
    if (url.endsWith('/segments')) {
      if (segmentsError) return Promise.reject(segmentsError)
      return Promise.resolve({ data: segments })
    }
    return Promise.resolve({ data: {} })
  })
  const { useGameList } = await import('@/composables/useGameList')
  const { useSegments } = await import('@/composables/useSegments')
  return { api, useGameList, useSegments }
}

beforeEach(() => {
  localStorage.clear()
})

describe('useSegments', () => {
  it('无选中游戏：loadSegments 早退，不发 /segments 请求且列表为空', async () => {
    const { api, useSegments } = await fresh({ games: [] })
    const { segments, loading } = useSegments()
    await useSegments().loadSegments()
    expect(segments.value).toEqual([])
    expect(loading.value).toBe(false)
    expect(api.get).not.toHaveBeenCalledWith(
      expect.stringMatching(/\/segments$/)
    )
  })

  it('选中游戏后加载：只保留 ACTIVE 分群', async () => {
    const { api, useSegments } = await fresh({
      selectedGame: 'g1', // 预置持久化，绕开导入期自动选中竞态
      segments: [
        { id: 's1', status: 'ACTIVE' },
        { id: 's2', status: 'PAUSED' }
      ]
    })
    const { segments, loading } = useSegments()
    await useSegments().loadSegments()
    expect(api.get).toHaveBeenCalledWith('/api/games/g1/segments')
    expect(segments.value).toHaveLength(1)
    expect(segments.value[0].id).toBe('s1')
    expect(loading.value).toBe(false)
  })

  it('切换游戏：segmentId 重置且按新游戏重新加载', async () => {
    const { useGameList, useSegments } = await fresh()
    useGameList().selectGame('g1')
    const ctx = useSegments()
    await vi.waitFor(() => expect(ctx.segments.value).toHaveLength(0))
    ctx.segmentId.value = 's1'

    useGameList().selectGame('g2')
    await vi.waitFor(() => expect(ctx.segmentId.value).toBe(''))
    await vi.waitFor(() => expect(useSegments().loading.value).toBe(false))
    const api = (await import('@/services/api')).default
    expect(api.get).toHaveBeenCalledWith('/api/games/g2/segments')
  })

  it('接口失败：console.error 且分群列表清空', async () => {
    const errSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const { useGameList, useSegments } = await fresh({
      segments: [{ id: 's1', status: 'ACTIVE' }],
      segmentsError: new Error('boom')
    })
    useGameList().selectGame('g1')
    const { segments } = useSegments()
    await vi.waitFor(() => expect(errSpy).toHaveBeenCalled())
    expect(segments.value).toEqual([])
    errSpy.mockRestore()
  })

  it('响应为 null：|| 兜底空数组臂生效', async () => {
    const { api, useSegments } = await fresh({ selectedGame: 'g1' })
    // 覆盖 mock：segments 端点直接回 null（上一实现经自动选中已消费过默认 mock，无法命中最小臂）
    api.get.mockImplementation((url) =>
      url.endsWith('/segments') ? Promise.resolve({ data: null }) : Promise.resolve({ data: { content: [] } })
    )
    const { segments } = useSegments()
    await useSegments().loadSegments()
    expect(api.get).toHaveBeenCalledWith('/api/games/g1/segments')
    expect(segments.value).toEqual([])
  })

  it('单例语义：多次 useSegments() 返回同一批 ref', () => {
    return fresh().then(({ useSegments }) => {
      const a = useSegments()
      const b = useSegments()
      expect(a.segments).toBe(b.segments)
      expect(a.segmentId).toBe(b.segmentId)
    })
  })
})
