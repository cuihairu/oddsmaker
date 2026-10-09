import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { settle } from '../helpers/settle.js'

/**
 * views/RiskLabView.vue：策略实验室复盘聚合页——
 * - 初载：rule-stats 拉取渲染汇总卡（扫描案例/误杀/规则数）与规则聚合行
 *   （误杀率、平均复盘、零案例规则 '-'、孤儿规则「已删规则」前缀）
 * - 下钻：点规则行 → risk-cases 样本请求带 ruleId+limit、无 disposition；样本表渲染
 * - 处置筛选：chips 带 disposition 重拉；再点同一规则行收起样本区
 * - 空态：无规则出「暂无风控规则」
 *
 * useGameList 模块单例——每用例 resetModules + 先设 localStorage 再动态 import 视图；
 * GameSelector 打桩隔离（与 SegmentsView.spec 同口径）。
 */
vi.mock('@/services/api', () => ({ default: { get: vi.fn() } }))

const STORAGE_KEY = 'oddsmaker.selectedGameId'

const ok = (data) => new Promise((res) => setTimeout(() => res({ data }), 0))

const STATS = {
  gameId: 'g1',
  scannedCases: 6,
  totals: { totalCases: 6, totalFalsePositives: 2, rulesWithCases: 2 },
  rules: [
    {
      ruleId: 'rr_1', ruleName: '大额充值（显示名）', ruleStatus: 'ACTIVE', riskScore: 85,
      caseCount: 4, reviewedCount: 3, falsePositiveCount: 1, confirmedFraudCount: 2,
      inconclusiveCount: 0, unreviewedCount: 1, misKillRate: 25.0, avgReviewHours: 4.0,
      lastCaseAt: '2026-10-08T18:02:11'
    },
    {
      ruleId: 'rr_gone', ruleName: null, ruleStatus: null, riskScore: null,
      caseCount: 1, reviewedCount: 1, falsePositiveCount: 1, confirmedFraudCount: 0,
      inconclusiveCount: 0, unreviewedCount: 0, misKillRate: 100.0, avgReviewHours: 1.0,
      lastCaseAt: '2026-10-07T09:00:00'
    },
    {
      ruleId: 'rr_zero', ruleName: '零案例规则', ruleStatus: 'ACTIVE', riskScore: 30,
      caseCount: 0, reviewedCount: 0, falsePositiveCount: 0, confirmedFraudCount: 0,
      inconclusiveCount: 0, unreviewedCount: 0, misKillRate: null, avgReviewHours: null,
      lastCaseAt: null
    }
  ]
}

const SAMPLES = [
  {
    id: 'rc_1', caseNumber: 'CASE_20261009_0001', riskLevel: 'HIGH', status: 'BLOCK',
    disposition: 'confirmed_benign', targetType: 'player_id', targetId: 'p_100',
    createdAt: '2026-10-08T18:02:11'
  },
  {
    id: 'rc_2', caseNumber: 'CASE_20261007_0007', riskLevel: 'CRITICAL', status: 'RESOLVED',
    disposition: 'confirmed_fraud', targetType: 'player_id', targetId: 'p_200',
    createdAt: '2026-10-07T09:00:00'
  }
]

async function fresh({ stats = STATS, samples = SAMPLES } = {}) {
  vi.resetModules()
  localStorage.clear()
  localStorage.setItem(STORAGE_KEY, 'g1')

  const api = (await import('@/services/api')).default
  api.get.mockImplementation((url) => {
    if (url === '/api/games') return ok({ content: [{ id: 'g1' }] })
    if (url === '/api/games/g1/risk-lab/rule-stats') return ok(stats)
    if (url === '/api/games/g1/risk-cases') return ok(samples)
    return ok({})
  })

  const RiskLabView = (await import('@/views/RiskLabView.vue')).default
  const wrapper = mount(RiskLabView, { global: { stubs: { GameSelector: true } } })
  await settle()
  return { wrapper, api }
}

const ruleRows = (w) => w.findAll('tbody')[0].findAll('tr')
const sampleRows = (w) => w.findAll('tbody')[1]?.findAll('tr') ?? []
const caseCalls = (api) => api.get.mock.calls.filter((c) => c[0].endsWith('/risk-cases'))

describe('RiskLabView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('初载：渲染汇总卡与规则聚合行（误杀率/复盘时长/零案例与孤儿规则口径）', async () => {
    const { wrapper } = await fresh()

    expect(wrapper.text()).toContain('扫描案例（最近窗口）')
    expect(wrapper.text()).toContain('6')
    expect(wrapper.text()).toContain('大额充值（显示名）')
    expect(wrapper.text()).toContain('25%')
    expect(wrapper.text()).toContain('4')
    // 孤儿规则带「已删规则」前缀、零案例行误杀率与时长为 '-'
    expect(wrapper.text()).toContain('（已删规则）rr_gone')
    expect(wrapper.text()).toContain('零案例规则')
    // 未选中规则时无样本区
    expect(wrapper.text()).not.toContain('案例样本：')
  })

  it('下钻：点规则行拉案例样本（ruleId + limit、不带 disposition），再点同行收起', async () => {
    const { wrapper, api } = await fresh()

    await ruleRows(wrapper)[0].trigger('click')
    await settle()

    expect(wrapper.text()).toContain('案例样本：大额充值（显示名）')
    expect(wrapper.text()).toContain('CASE_20261009_0001')
    expect(wrapper.text()).toContain('误杀（确认正常）')
    expect(caseCalls(api)).toHaveLength(1)
    expect(caseCalls(api)[0][1]).toEqual({ params: { ruleId: 'rr_1', limit: 100 } })

    await ruleRows(wrapper)[0].trigger('click')
    await settle()
    expect(wrapper.text()).not.toContain('案例样本：')
  })

  it('处置筛选：点「误杀」chips 以 disposition 重拉样本', async () => {
    const { wrapper, api } = await fresh()

    await ruleRows(wrapper)[0].trigger('click')
    await settle()
    const benignChip = wrapper.findAll('button').find((b) => b.text() === '误杀')
    await benignChip.trigger('click')
    await settle()

    expect(caseCalls(api)).toHaveLength(2)
    expect(caseCalls(api)[1][1]).toEqual({ params: { ruleId: 'rr_1', limit: 100, disposition: 'confirmed_benign' } })
  })

  it('空态：无规则出提示', async () => {
    const { wrapper } = await fresh({
      stats: { gameId: 'g1', scannedCases: 0, totals: { totalCases: 0, totalFalsePositives: 0, rulesWithCases: 0 }, rules: [] }
    })

    expect(wrapper.text()).toContain('暂无风控规则')
  })
})
