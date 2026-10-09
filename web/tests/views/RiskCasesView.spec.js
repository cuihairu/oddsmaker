import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { settle } from '../helpers/settle.js'

/**
 * views/RiskCasesView.vue：风控案例回看页——
 * - 初载：案例列表渲染（案例号/等级/状态/目标）
 * - 详情弹层：点行拉详情；subjectRiskScore 快照存在时渲染「主体累计分」行
 *   （分数 + 规则贡献条数 + 快照时间），不存在时该行隐藏（CH 未配置/未落分降级）
 *
 * useGameList 模块单例——每用例 resetModules + 先设 localStorage 再动态 import 视图；
 * GameSelector 打桩隔离（与 RiskLabView.spec 同口径）。
 */
vi.mock('@/services/api', () => ({ default: { get: vi.fn(), post: vi.fn(), delete: vi.fn() } }))

const STORAGE_KEY = 'oddsmaker.selectedGameId'

const ok = (data) => new Promise((res) => setTimeout(() => res({ data }), 0))

const CASES = [
  {
    id: 'rc_1', caseNumber: 'CASE_20261009_0001', riskLevel: 'HIGH', status: 'BLOCK',
    actionTaken: 'BLOCK', executionStatus: 'EXECUTED', targetType: 'player_id',
    targetId: 'p_100', createdAt: '2026-10-09T18:02:11'
  },
  {
    id: 'rc_2', caseNumber: 'CASE_20261007_0007', riskLevel: 'CRITICAL', status: 'RESOLVED',
    actionTaken: 'ALERT', executionStatus: 'EXECUTED', targetType: 'user_id',
    targetId: 'u_200', createdAt: '2026-10-07T09:00:00'
  }
]

const DETAIL_WITH_SCORE = {
  id: 'rc_1', caseNumber: 'CASE_20261009_0001', riskLevel: 'HIGH', status: 'BLOCK',
  targetType: 'player_id', targetId: 'p_100', executionStatus: 'EXECUTED',
  subjectRiskScore: {
    found: true, score: 85,
    reasons: [{ ruleId: 'rr_1', contribution: 40 }, { ruleId: 'rr_2', contribution: 25 }],
    updatedAt: '2026-10-10 12:00:00'
  }
}

const DETAIL_WITHOUT_SCORE = {
  id: 'rc_2', caseNumber: 'CASE_20261007_0007', riskLevel: 'CRITICAL', status: 'RESOLVED',
  targetType: 'user_id', targetId: 'u_200', subjectRiskScore: null
}

async function fresh({ cases = CASES, detail = DETAIL_WITH_SCORE, detailId = 'rc_1' } = {}) {
  vi.resetModules()
  localStorage.clear()
  localStorage.setItem(STORAGE_KEY, 'g1')

  const api = (await import('@/services/api')).default
  api.get.mockImplementation((url) => {
    if (url === '/api/games') return ok({ content: [{ id: 'g1' }] })
    if (url.startsWith('/api/games/g1/risk-cases/')) {
      return url.endsWith(`/${detailId}`) ? ok(detail) : ok({})
    }
    if (url === '/api/games/g1/risk-cases') return ok(cases)
    return ok({})
  })

  const RiskCasesView = (await import('@/views/RiskCasesView.vue')).default
  const wrapper = mount(RiskCasesView, { global: { stubs: { GameSelector: true } } })
  await settle()
  return { wrapper, api }
}

const caseRows = (w) => w.findAll('tbody')[0].findAll('tr')
const openRowDetail = async (w, i) => {
  const btn = caseRows(w)[i].findAll('button').find((b) => b.text() === '详情')
  await btn.trigger('click')
  await settle()
}

describe('RiskCasesView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('初载：渲染案例列表行', async () => {
    const { wrapper } = await fresh()

    expect(wrapper.text()).toContain('CASE_20261009_0001')
    expect(wrapper.text()).toContain('CASE_20261007_0007')
    expect(caseRows(wrapper)).toHaveLength(2)
  })

  it('详情：subjectRiskScore 快照渲染主体累计分行，缺失时隐藏', async () => {
    const { wrapper } = await fresh()

    await openRowDetail(wrapper, 0)
    expect(wrapper.text()).toContain('主体累计分：')
    expect(wrapper.text()).toContain('85')
    expect(wrapper.text()).toContain('2 条规则贡献')
  })

  it('详情：主体未落分（subjectRiskScore=null）不渲染累计分行', async () => {
    const { wrapper } = await fresh({ detail: DETAIL_WITHOUT_SCORE, detailId: 'rc_2' })

    await openRowDetail(wrapper, 1)
    expect(wrapper.text()).toContain('u_200')
    expect(wrapper.text()).not.toContain('主体累计分：')
  })
})
