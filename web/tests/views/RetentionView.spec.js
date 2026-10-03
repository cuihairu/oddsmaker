import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { settle } from '../helpers/settle.js'

/**
 * views/RetentionView.vue：留存报表的数据流与渲染分支。
 * - 未选游戏不发请求；选中后按 granularity/days/segment 组合拉取趋势
 * - 汇总卡片 pct 格式化与 ?? 0 兜底；cohort 明细表；真实 TrendChart 出 polyline
 * - 错误臂（后端 message / 兜底文案）、available:false（ClickHouse 未配置）分支
 *
 * 组件依赖 useGameList/useSegments 模块单例——每用例 resetModules + 先设 localStorage
 * 再动态 import 视图，保证单例从干净状态起步。GameSelector 打桩隔离选择器 UI。
 */
vi.mock('@/services/api', () => ({ default: { get: vi.fn(), post: vi.fn() } }))

const STORAGE_KEY = 'oddsmaker.selectedGameId'

async function fresh({
  selectedGame = null,
  games = [{ id: 'g1' }, { id: 'g2' }],
  segments = [],
  retention = {},
  retentionError = null
} = {}) {
  vi.resetModules()
  localStorage.clear()
  if (selectedGame) localStorage.setItem(STORAGE_KEY, selectedGame)

  const api = (await import('@/services/api')).default
  api.get.mockImplementation((url) => {
    if (url === '/api/games') return Promise.resolve({ data: { content: games } })
    if (url.includes('/segments')) return Promise.resolve({ data: segments })
    if (url.includes('/trend')) {
      if (retentionError) return Promise.reject(retentionError)
      return Promise.resolve({ data: retention })
    }
    return Promise.resolve({ data: {} })
  })
  const RetentionView = (await import('@/views/RetentionView.vue')).default
  return { api, RetentionView }
}

function mountView(RetentionView) {
  return mount(RetentionView, { global: { stubs: { GameSelector: true } } })
}

const trendCalls = (api) => api.get.mock.calls.filter((c) => c[0].includes('/trend'))

const POINT = {
  cohort: '2026-09-01',
  newUsers: 100,
  d1: 40, d1Rate: 0.4,
  d7: 20, d7Rate: 0.2,
  d30: 10, d30Rate: 0.1
}
const SUMMARY = {
  totalNewUsers: 100, cohorts: 1,
  avgD1Rate: 0.4, avgD7Rate: 0.2, avgD30Rate: 0.1, matureCohortsD30: 1
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('RetentionView', () => {
  it('无可用游戏：不发趋势请求，页面只有头部', async () => {
    const { api, RetentionView } = await fresh({ games: [] })
    const w = mountView(RetentionView)
    await settle()
    expect(api.get).toHaveBeenCalledWith('/api/games')
    expect(trendCalls(api)).toHaveLength(0)
    expect(w.text()).toContain('留存趋势报表')
    expect(w.text()).not.toContain('窗口新增用户')
  })

  it('拉取趋势：默认 day/90、无 segment；汇总卡 pct 格式化、明细表、真实 TrendChart 出 3 条折线', async () => {
    const { api, RetentionView } = await fresh({
      selectedGame: 'g1',
      retention: { points: [POINT], summary: SUMMARY }
    })
    const w = mountView(RetentionView)
    await settle()

    expect(trendCalls(api)).toHaveLength(1)
    expect(trendCalls(api)[0][0]).toBe('/api/retention-metrics/g1/trend')
    // segmentId 为空串时按现状传 undefined（axios 会剥掉该参数）
    expect(trendCalls(api)[0][1]).toEqual({
      params: { granularity: 'day', days: 90, segment_id: undefined }
    })

    const text = w.text()
    expect(text).toContain('100') // 窗口新增用户
    expect(text).toContain('1 个 cohort')
    expect(text).toContain('40.00%') // 平均次留 D1（summary + 明细行）
    expect(text).toContain('20.00%')
    expect(text).toContain('10.00%')
    expect(text).toContain('仅统计 1 个成熟 cohort')
    expect(text).toContain('2026-09-01') // cohort 明细行
    expect(w.findAll('polyline')).toHaveLength(3) // D1/D7/D30 三条序列
  })

  it('空数据兜底：summary 缺省 ?? 0、points 空 → 「窗口期内无留存数据」', async () => {
    const { api, RetentionView } = await fresh({ selectedGame: 'g1', retention: {} })
    const w = mountView(RetentionView)
    await settle()
    expect(trendCalls(api)).toHaveLength(1)
    const text = w.text()
    expect(text).toContain('窗口期内无留存数据')
    expect(text).toContain('0 个 cohort')
    expect(text).toContain('0.00%') // pct(0) 兜底
  })

  it('切换粒度 granularity → watch 重拉，参数带 week', async () => {
    const { api, RetentionView } = await fresh({ selectedGame: 'g1', retention: {} })
    const w = mountView(RetentionView)
    await settle()

    await w.findAll('select')[0].setValue('week')
    await settle()
    expect(trendCalls(api)).toHaveLength(2)
    expect(trendCalls(api)[1][1].params.granularity).toBe('week')
  })

  it('切换时间窗 days → watch 重拉，参数带 180（option 绑定数字）', async () => {
    const { api, RetentionView } = await fresh({ selectedGame: 'g1', retention: {} })
    const w = mountView(RetentionView)
    await settle()

    await w.findAll('select')[1].setValue('180')
    await settle()
    expect(trendCalls(api)[1][1].params.days).toBe(180)
  })

  it('分群下拉只列 ACTIVE；选中后 segment_id 进查询参数', async () => {
    const { api, RetentionView } = await fresh({
      selectedGame: 'g1',
      segments: [
        { id: 'seg-1', name: '高价值', status: 'ACTIVE' },
        { id: 'seg-2', name: '暂停中', status: 'PAUSED' }
      ]
    })
    const w = mountView(RetentionView)
    await settle()

    const text = w.text()
    expect(text).toContain('高价值')
    expect(text).not.toContain('暂停中') // 非 ACTIVE 不进下拉
    expect(trendCalls(api)[0][1].params.segment_id).toBeUndefined()

    await w.findAll('select')[2].setValue('seg-1')
    await settle()
    expect(trendCalls(api)[1][1].params.segment_id).toBe('seg-1')
  })

  it('失败臂（后端 message）：错误卡片展示后端文案', async () => {
    const { RetentionView } = await fresh({
      selectedGame: 'g1',
      retentionError: { response: { data: { message: '后端炸了' } } }
    })
    const w = mountView(RetentionView)
    await settle()
    expect(w.text()).toContain('后端炸了')
  })

  it('失败臂（无 response）：兜底文案「加载留存趋势失败」', async () => {
    const { RetentionView } = await fresh({ selectedGame: 'g1', retentionError: new Error('x') })
    const w = mountView(RetentionView)
    await settle()
    expect(w.text()).toContain('加载留存趋势失败')
  })

  it('available:false：ClickHouse 未配置提示', async () => {
    const { RetentionView } = await fresh({ selectedGame: 'g1', retention: { available: false } })
    const w = mountView(RetentionView)
    await settle()
    expect(w.text()).toContain('ClickHouse 未配置')
    expect(w.text()).toContain('CLICKHOUSE_URL')
  })

  it('刷新按钮：手动再拉一次', async () => {
    const { api, RetentionView } = await fresh({ selectedGame: 'g1', retention: {} })
    const w = mountView(RetentionView)
    await settle()
    await w.findAll('button').find((b) => b.text() === '刷新').trigger('click')
    await settle()
    expect(trendCalls(api)).toHaveLength(2)
  })
})
