import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { settle } from '../helpers/settle.js'

/**
 * views/RiskLabView.vue：策略实验室复盘聚合页——
 * - 初载：rule-stats 拉取渲染汇总卡（扫描案例/误杀/规则数）与规则聚合行
 *   （误杀率、平均复盘、零案例规则 '-'、孤儿规则「已删规则」前缀）
 * - 下钻：点规则行 → risk-cases 样本请求带 ruleId+limit、无 disposition；样本表渲染
 * - 处置筛选：chips 带 disposition 重拉；再点同一规则行收起样本区
 * - 试算回放：载入示例 → POST /risk-lab/replay（samples 必带、ruleIds 过滤可选）、
 *   渲染命中汇总/规则表/逐样本生效徽章；非法 JSON 本地报错不发请求；服务端 400 透出
 * - 空态：无规则出「暂无风控规则」
 *
 * useGameList 模块单例——每用例 resetModules + 先设 localStorage 再动态 import 视图；
 * GameSelector 打桩隔离（与 SegmentsView.spec 同口径）。
 */
vi.mock('@/services/api', () => ({ default: { get: vi.fn(), post: vi.fn() } }))

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

const REPLAY = {
  gameId: 'g1',
  summary: { totalSamples: 2, hitSamples: 1, hitRules: 1, skippedRules: 1 },
  ruleResults: [
    {
      ruleId: 'rr_1', ruleName: '大额充值', ruleType: 'THRESHOLD', status: 'evaluable',
      skipReason: null, hitSamples: 1, sampleIds: ['evt-1'], riskScore: 85,
      riskLevel: 'HIGH', actionType: 'ALERT'
    },
    {
      ruleId: 'rr_2', ruleName: '频次异常', ruleType: 'FREQUENCY', status: 'needsStreaming',
      skipReason: '依赖流式窗口/序列聚合，dry-run 不模拟', hitSamples: 0, sampleIds: [],
      riskScore: 60, riskLevel: 'MEDIUM', actionType: 'ALERT'
    }
  ],
  sampleResults: [
    {
      eventId: 'evt-1', matchedRuleIds: ['rr_1'],
      effectiveHits: [{ ruleId: 'rr_1', ruleName: '大额充值', ruleType: 'THRESHOLD', riskScore: 85, riskLevel: 'HIGH', actionType: 'ALERT' }]
    },
    { eventId: 'evt-2', matchedRuleIds: [], effectiveHits: [] }
  ]
}

async function fresh({ stats = STATS, samples = SAMPLES, replay = REPLAY } = {}) {
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
  api.post.mockResolvedValue(ok(replay))

  const RiskLabView = (await import('@/views/RiskLabView.vue')).default
  const wrapper = mount(RiskLabView, { global: { stubs: { GameSelector: true } } })
  await settle()
  return { wrapper, api }
}

const ruleRows = (w) => w.findAll('tbody')[0].findAll('tr')
const sampleRows = (w) => w.findAll('tbody')[1]?.findAll('tr') ?? []
const caseCalls = (api) => api.get.mock.calls.filter((c) => c[0].endsWith('/risk-cases'))
const replayCalls = (api) => api.post.mock.calls.filter((c) => c[0].endsWith('/risk-lab/replay'))
const runReplay = async (w) => {
  const btn = w.findAll('button').find((b) => b.text() === '试算')
  await btn.trigger('click')
  await settle()
}

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

  it('试算回放：载入示例后试算，POST samples 且渲染命中汇总/规则表/生效徽章', async () => {
    const { wrapper, api } = await fresh()

    const exampleBtn = wrapper.findAll('button').find((b) => b.text() === '载入示例')
    await exampleBtn.trigger('click')
    expect(wrapper.find('textarea').element.value).toContain('evt-1')

    await runReplay(wrapper)

    expect(replayCalls(api)).toHaveLength(1)
    expect(replayCalls(api)[0][0]).toBe('/api/games/g1/risk-lab/replay')
    expect(replayCalls(api)[0][1].samples).toHaveLength(2)
    expect(replayCalls(api)[0][1].ruleIds).toBeUndefined()
    expect(wrapper.text()).toContain('命中样本')
    expect(wrapper.text()).toContain('大额充值 · 85分 · ALERT')
    expect(wrapper.text()).toContain('需流式窗口')
    expect(wrapper.text()).toContain('未命中')
  })

  it('试算回放：非法 JSON 本地报错且不发请求', async () => {
    const { wrapper, api } = await fresh()

    await wrapper.find('textarea').setValue('{bad json')
    await runReplay(wrapper)

    expect(wrapper.text()).toContain('样本不是合法 JSON')
    expect(replayCalls(api)).toHaveLength(0)
  })

  it('试算回放：规则 ID 过滤进 payload；服务端 400 消息透出', async () => {
    const { wrapper, api } = await fresh()
    api.post.mockRejectedValueOnce({ response: { data: { message: 'samples 不能为空' } } })

    await wrapper.find('textarea').setValue('[{"eventId":"s1","amount":500}]')
    await wrapper.find('input').setValue('rr_1, rr_2')
    await runReplay(wrapper)
    expect(replayCalls(api)[0][1].ruleIds).toEqual(['rr_1', 'rr_2'])
    expect(wrapper.text()).toContain('samples 不能为空')
  })
})
