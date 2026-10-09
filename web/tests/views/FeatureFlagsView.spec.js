import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { settle } from '../helpers/settle.js'

/**
 * views/FeatureFlagsView.vue：创建/编辑全字段表单（补齐此前只读+启停/灰度的面）——
 * - 列表加载与空态文案
 * - 创建弹层：flagKey/flagName 必填校验、名单按逗号拆成数组、POST 后重载
 * - 编辑弹层：预填（JSON 名单转逗号文本）、PUT 到 flagKey、状态不被表单触碰
 * - 启停操作 POST 走既有端点
 *
 * useAuthStore 以模块 mock 提供（避免引入 pinia）；api 三方法全 mock。
 */
vi.mock('@/services/api', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn() }
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ userName: 'tester' })
}))

const ok = (data) => new Promise((res) => setTimeout(() => res({ data }), 0))

const FLAG = {
  id: '1',
  flagKey: 'new_flag',
  flagName: '新开关',
  flagStatus: 'DISABLED',
  flagType: 'BOOLEAN',
  defaultValue: false,
  percentageValue: 0,
  whitelistUsers: '["u1","u10"]',
  blacklistUsers: null,
  conditions: null,
  rolloutSteps: '[10,50]',
  expiryDate: null
}

async function fresh({ flags = [FLAG] } = {}) {
  vi.resetModules()
  const api = (await import('@/services/api')).default
  api.get.mockImplementation((url) => {
    if (url === '/api/system/features') return ok(flags)
    return ok([])
  })
  api.post.mockClear()
  api.put.mockClear()
  const { default: FeatureFlagsView } = await import('@/views/FeatureFlagsView.vue')
  const wrapper = mount(FeatureFlagsView)
  await flushPromises()
  await settle(wrapper)
  return { wrapper, api }
}

describe('FeatureFlagsView', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('加载开关列表并渲染行', async () => {
    const { wrapper } = await fresh()
    expect(wrapper.text()).toContain('新开关')
    expect(wrapper.text()).toContain('new_flag')
  })

  it('创建：必填校验拦截空 flagKey，合法提交按逗号拆名单并 POST', async () => {
    const { wrapper, api } = await fresh()
    api.post.mockResolvedValue(ok({ ...FLAG, flagKey: 'k1', flagName: 'K1' }))
    api.get.mockResolvedValue(ok([{ ...FLAG, flagKey: 'k1', flagName: 'K1' }]))

    await wrapper.find('button.btn-primary').trigger('click')  // 新建开关
    await settle(wrapper)

    const saveBtn = wrapper.findAll('button').find(b => b.text() === '创建')
    await saveBtn.trigger('click')
    await settle(wrapper)
    expect(wrapper.text()).toContain('请填写开关名称')
    expect(api.post).not.toHaveBeenCalled()

    const inputs = wrapper.findAll('input.input')
    await inputs[1].setValue('K1')          // flagName（先过名称校验）
    await saveBtn.trigger('click')
    await settle(wrapper)
    expect(wrapper.text()).toContain('请填写开关键（flagKey）')
    expect(api.post).not.toHaveBeenCalled()

    await inputs[0].setValue('k1')          // flagKey
    const textareas = wrapper.findAll('textarea')
    await textareas[0].setValue('u1, u10 ,')  // 白名单（含空段）
    await saveBtn.trigger('click')
    await flushPromises()
    await settle(wrapper)

    expect(api.post).toHaveBeenCalledWith('/api/system/features', expect.objectContaining({
      flagKey: 'k1',
      flagName: 'K1',
      whitelistUsers: ['u1', 'u10'],
      blacklistUsers: [],
      createdBy: 'tester'
    }))
    expect(wrapper.text()).toContain('已创建')
  })

  it('编辑：预填名单为逗号文本，PUT 到 flagKey 且不带状态字段', async () => {
    const { wrapper, api } = await fresh()
    api.put.mockResolvedValue(ok({ ...FLAG, flagName: '改名' }))
    api.get.mockResolvedValue(ok([{ ...FLAG, flagName: '改名' }]))

    const editBtn = wrapper.findAll('button').find(b => b.text() === '编辑')
    await editBtn.trigger('click')
    await settle(wrapper)

    expect(wrapper.text()).toContain('编辑：new_flag')
    const textareas = wrapper.findAll('textarea')
    expect(textareas[0].element.value).toBe('u1,u10')  // JSON 名单 → 逗号文本

    const saveBtn = wrapper.findAll('button').find(b => b.text() === '保存')
    await saveBtn.trigger('click')
    await flushPromises()
    await settle(wrapper)

    expect(api.put).toHaveBeenCalledWith('/api/system/features/new_flag', expect.objectContaining({
      flagName: '新开关',
      whitelistUsers: ['u1', 'u10'],
      modifiedBy: 'tester'
    }))
    const payload = api.put.mock.calls[0][1]
    expect(payload.flagStatus).toBeUndefined()
    expect(payload.percentageValue).toBeUndefined()
    expect(wrapper.text()).toContain('已更新')
  })

  it('启停：POST 走既有 enable 端点', async () => {
    const { wrapper, api } = await fresh()
    api.post.mockResolvedValue(ok({ ...FLAG, flagStatus: 'ENABLED' }))

    const enableBtn = wrapper.findAll('button').find(b => b.text() === '启用')
    await enableBtn.trigger('click')
    await flushPromises()
    await settle(wrapper)

    expect(api.post).toHaveBeenCalledWith('/api/system/features/new_flag/enable', { modifiedBy: 'tester' })
    expect(wrapper.text()).toContain('已启用')
  })
})
