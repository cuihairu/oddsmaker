import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { settle } from '../helpers/settle.js'

/**
 * components/MetricAlertBell.vue：图表级告警铃铛入口（调研「告警配置贴图表」落点）。
 * 依赖 useGameList 模块单例 → resetModules + 动态 import（与 GameSelector 测试同款）。
 * 契约：铃铛点击开弹层；规则名缺省「<指标>告警」；POST /api/games/{id}/alert-rules
 * 带 metricType 与条件字段；ABSOLUTE 模式传 threshold 不传 deviationPct；成功后弹层关闭并短暂显示已创建。
 */
vi.mock('@/services/api', () => ({ default: { get: vi.fn(), post: vi.fn() } }))

async function fresh(metricType = 'CRASH_RATE') {
  vi.resetModules()
  localStorage.clear()
  const api = (await import('@/services/api')).default
  api.get.mockResolvedValue({ data: { content: [{ id: 'g1', name: 'one' }] } })
  api.post.mockResolvedValue({ data: {} })
  const MetricAlertBell = (await import('@/components/MetricAlertBell.vue')).default
  return { api, MetricAlertBell }
}

beforeEach(() => {
  localStorage.clear()
})

describe('MetricAlertBell', () => {
  it('铃铛渲染悬浮提示，点击打开弹层并带指标名缺省规则名', async () => {
    const { MetricAlertBell } = await fresh('CRASH_RATE')
    const w = mount(MetricAlertBell, { props: { metricType: 'CRASH_RATE', title: '为崩溃率配置告警' } })
    await settle()

    const bell = w.find('button[aria-label="配置告警"]')
    expect(bell.attributes('title')).toBe('为崩溃率配置告警')

    await bell.trigger('click')
    await settle()
    expect(w.text()).toContain('新建崩溃率告警')
    expect(w.find('input.input').element.value).toBe('崩溃率告警')
  })

  it('规则名为空时拒绝保存且不发请求', async () => {
    const { api, MetricAlertBell } = await fresh()
    const w = mount(MetricAlertBell, { props: { metricType: 'DAU' } })
    await settle()
    await w.find('button[aria-label="配置告警"]').trigger('click')
    await settle()

    const nameInput = w.findAll('input.input')[0]
    await nameInput.setValue('   ')
    const saveBtn = w.findAll('button').find(b => b.text() === '创建规则')
    await saveBtn.trigger('click')
    await settle()

    expect(w.text()).toContain('请填写规则名称')
    expect(api.post).not.toHaveBeenCalled()
  })

  it('偏差模式：POST 带 metricType/deviationPct/enabled，成功后弹层关闭并显示已创建', async () => {
    const { api, MetricAlertBell } = await fresh('REVENUE')
    const w = mount(MetricAlertBell, { props: { metricType: 'REVENUE' } })
    await settle()
    await w.find('button[aria-label="配置告警"]').trigger('click')
    await settle()

    const saveBtn = w.findAll('button').find(b => b.text() === '创建规则')
    await saveBtn.trigger('click')
    await settle()

    expect(api.post).toHaveBeenCalledWith('/api/games/g1/alert-rules', expect.objectContaining({
      name: '收入告警',
      metricType: 'REVENUE',
      conditionType: 'BASELINE_DEVIATION',
      comparison: 'LT',
      threshold: null,
      deviationPct: 30,
      window: 'TODAY',
      severity: 'WARNING',
      enabled: true,
      notifyWebhook: true
    }))
    expect(w.find('.fixed.inset-0').exists()).toBe(false)
    expect(w.text()).toContain('已创建')
  })

  it('固定阈值模式：切 ABSOLUTE 后 POST threshold 数值且 deviationPct 为 null', async () => {
    const { api, MetricAlertBell } = await fresh('DAU')
    const w = mount(MetricAlertBell, { props: { metricType: 'DAU' } })
    await settle()
    await w.find('button[aria-label="配置告警"]').trigger('click')
    await settle()

    const selects = w.findAll('select')
    // selects[0] = 条件（metricType 不渲染 select，弹层内第一个 select 是条件）
    await selects[0].setValue('ABSOLUTE')
    await settle()

    const thresholdInput = w.find('input[type="number"]')
    expect(thresholdInput.exists()).toBe(true)
    await thresholdInput.setValue(500)

    const saveBtn = w.findAll('button').find(b => b.text() === '创建规则')
    await saveBtn.trigger('click')
    await settle()

    expect(api.post).toHaveBeenCalledWith('/api/games/g1/alert-rules', expect.objectContaining({
      metricType: 'DAU',
      conditionType: 'ABSOLUTE',
      threshold: 500,
      deviationPct: null
    }))
  })

  it('取消按钮关闭弹层且不发请求', async () => {
    const { api, MetricAlertBell } = await fresh()
    const w = mount(MetricAlertBell, { props: { metricType: 'DAU' } })
    await settle()
    await w.find('button[aria-label="配置告警"]').trigger('click')
    await settle()

    const cancelBtn = w.findAll('button').find(b => b.text() === '取消')
    await cancelBtn.trigger('click')
    await settle()

    expect(w.find('.fixed.inset-0').exists()).toBe(false)
    expect(api.post).not.toHaveBeenCalled()
  })
})
