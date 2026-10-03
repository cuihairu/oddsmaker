import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { settle } from '../helpers/settle.js'

/**
 * views/GamesView.vue：加载渲染、空态、卡片点击跳转、创建表单的成功/失败流。
 * vue-router 的 useRouter 与 services/api 均 mock；TrendChart 不涉及。
 */
const { pushMock } = vi.hoisted(() => ({ pushMock: vi.fn() }))

vi.mock('vue-router', () => ({ useRouter: () => ({ push: pushMock }) }))

vi.mock('@/services/api', () => ({ default: { get: vi.fn(), post: vi.fn() } }))

import api from '@/services/api'
import GamesView from '@/views/GamesView.vue'

const GAME = {
  id: 'g1',
  name: 'demo',
  displayName: '演示游戏',
  description: '一款演示用游戏',
  status: 'LIVE',
  genre: 'RPG',
  platforms: ['MOBILE', 'WEB'],
  createdAt: '2026-01-15T00:00:00Z'
}

async function mountView() {
  const w = mount(GamesView)
  await settle()
  return w
}

beforeEach(() => {
  pushMock.mockClear()
  vi.clearAllMocks()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('GamesView', () => {
  it('加载完成：渲染卡片（displayName、状态/类型徽标、平台、描述）且 loading 消失', async () => {
    api.get.mockResolvedValue({ data: { content: [GAME] } })
    const w = await mountView()
    const text = w.text()
    expect(text).not.toContain('加载中')
    expect(text).toContain('演示游戏')
    expect(text).toContain('已上线') // LIVE → 中文状态标签
    expect(text).toContain('角色扮演') // RPG → 中文类型标签
    expect(text).toContain('MOBILE')
    expect(text).toContain('WEB')
    expect(text).toContain('一款演示用游戏')
    expect(text).toContain('创建于')
    expect(api.get).toHaveBeenCalledWith('/api/games')
  })

  it('displayName 缺省回退 name；未知 status/genre 原样透出且走默认灰色徽标', async () => {
    api.get.mockResolvedValue({
      data: { content: [{ ...GAME, displayName: undefined, status: 'WEIRD', genre: 'MYSTERY' }] }
    })
    const w = await mountView()
    expect(w.text()).toContain('demo')
    expect(w.text()).toContain('WEIRD')
    expect(w.text()).toContain('MYSTERY')
    const badge = w.findAll('.badge').find((b) => b.text() === 'WEIRD')
    expect(badge.classes()).toContain('bg-gray-100')
  })

  it('空列表：显示空态引导', async () => {
    api.get.mockResolvedValue({ data: { content: [] } })
    const w = await mountView()
    expect(w.text()).toContain('暂无游戏')
    expect(w.text()).toContain('开始创建您的第一个游戏项目')
  })

  it('加载失败：console.error 并落到空态', async () => {
    const errSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    api.get.mockRejectedValue(new Error('boom'))
    const w = await mountView()
    expect(errSpy).toHaveBeenCalled()
    expect(w.text()).toContain('暂无游戏')
  })

  it('点击卡片 → 跳转 /games/:id', async () => {
    api.get.mockResolvedValue({ data: { content: [GAME] } })
    const w = await mountView()
    await w.find('.card').trigger('click')
    expect(pushMock).toHaveBeenCalledWith('/games/g1')
  })

  it('创建成功：POST 表单数据 → 关闭弹窗并重载列表', async () => {
    api.get.mockResolvedValue({ data: { content: [] } })
    api.post.mockResolvedValue({ data: {} })
    const w = await mountView()

    await w.findAll('button').find((b) => b.text() === '创建游戏').trigger('click')
    expect(w.text()).toContain('创建新游戏')

    await w.find('input[placeholder="例如: game_demo"]').setValue('new_game')
    await w.find('input[placeholder="例如: 我的游戏"]').setValue('新游戏')
    await w.find('textarea').setValue('描述文案')
    await w.find('select').setValue('RPG')
    const pcBox = w.findAll('input[type="checkbox"]').find((c) => c.element.value === 'PC')
    await pcBox.setValue(true)

    await w.find('form').trigger('submit')
    await settle()

    expect(api.post).toHaveBeenCalledWith('/api/games', {
      name: 'new_game',
      displayName: '新游戏',
      description: '描述文案',
      genre: 'RPG',
      platforms: ['MOBILE', 'PC']
    })
    // 弹窗关闭 + 列表重载
    expect(w.text()).not.toContain('创建新游戏')
    expect(api.get).toHaveBeenCalledTimes(2)
  })

  it('创建失败（后端 message）：alert 提示、弹窗保持打开', async () => {
    const alertMock = vi.fn()
    vi.stubGlobal('alert', alertMock)
    api.get.mockResolvedValue({ data: { content: [] } })
    api.post.mockRejectedValue({ response: { data: { message: '名称已存在' } } })
    const w = await mountView()

    await w.findAll('button').find((b) => b.text() === '创建游戏').trigger('click')
    await w.find('input[placeholder="例如: game_demo"]').setValue('dup')
    await w.find('form').trigger('submit')
    await settle()

    expect(alertMock).toHaveBeenCalledWith('创建游戏失败: 名称已存在')
    expect(w.text()).toContain('创建新游戏')
    vi.unstubAllGlobals()
  })

  it('创建失败（无 response）：alert 回退到 error.message', async () => {
    const alertMock = vi.fn()
    vi.stubGlobal('alert', alertMock)
    api.get.mockResolvedValue({ data: { content: [] } })
    api.post.mockRejectedValue(new Error('网络中断'))
    const w = await mountView()

    await w.findAll('button').find((b) => b.text() === '创建游戏').trigger('click')
    await w.find('form').trigger('submit')
    await settle()

    expect(alertMock).toHaveBeenCalledWith('创建游戏失败: 网络中断')
    vi.unstubAllGlobals()
  })

  it('空态「创建游戏」按钮同样打开弹窗；取消按钮关闭弹窗；平台勾选可增可删', async () => {
    api.get.mockResolvedValue({ data: { content: [] } })
    const w = await mountView()

    // 空态里的创建按钮（非页头那颗）也能打开弹窗
    const emptyStateCreate = w.findAll('button').filter((b) => b.text() === '创建游戏')
    await emptyStateCreate[emptyStateCreate.length - 1].trigger('click')
    expect(w.text()).toContain('创建新游戏')

    // 平台勾选：6 个复选框全遍历（勾上未选的、取消已选的）→ 数组同步增删
    const boxes = () => w.findAll('input[type="checkbox"]')
    for (const box of boxes()) {
      await box.setValue(!box.element.checked)
    }
    await w.find('select').setValue('CASUAL')

    await w.findAll('button').find((b) => b.text() === '取消').trigger('click')
    expect(w.text()).not.toContain('创建新游戏')
  })
})
