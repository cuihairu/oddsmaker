import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

/**
 * components/GameSelector.vue：游戏切换器（报表页共用）。
 * 依赖 useGameList 模块单例 → 与 composable 测试同款 resetModules + 动态 import。
 * 契约：无游戏时禁用并显示占位项；有游戏时选项为 displayName‖name（id），选中值同步；
 * change 事件 → selectGame 持久化 + 向父级 emit。
 */
vi.mock('@/services/api', () => ({ default: { get: vi.fn(), post: vi.fn() } }))

async function fresh({ games = [{ id: 'g1', name: 'one', displayName: '游戏一' }, { id: 'g2', name: 'two' }] } = {}) {
  vi.resetModules()
  localStorage.clear()
  const api = (await import('@/services/api')).default
  api.get.mockResolvedValue({ data: { content: games } })
  const GameSelector = (await import('@/components/GameSelector.vue')).default
  const { useGameList } = await import('@/composables/useGameList')
  return { api, GameSelector, useGameList }
}

beforeEach(() => {
  localStorage.clear()
})

describe('GameSelector', () => {
  it('无游戏：下拉禁用并显示占位项', async () => {
    const { GameSelector } = await fresh({ games: [] })
    const w = mount(GameSelector)
    await flushPromises()
    const select = w.find('select')
    expect(select.attributes('disabled')).toBeDefined()
    expect(w.text()).toContain('暂无可用游戏')
  })

  it('有游戏：选项渲染 displayName 回退 name 并附 id，选中值与 currentGameId 同步', async () => {
    const { GameSelector, useGameList } = await fresh()
    const w = mount(GameSelector)
    await flushPromises()

    const options = w.findAll('option')
    expect(options).toHaveLength(2)
    expect(options[0].text()).toContain('游戏一')
    expect(options[0].text()).toContain('（g1）')
    expect(options[1].text()).toContain('two') // displayName 缺省回退 name

    // 加载后自动选中第一个游戏（useGameList 契约），select 跟随
    expect(useGameList().currentGameId.value).toBe('g1')
    await flushPromises()
    expect(w.find('select').element.value).toBe('g1')
    expect(w.find('select').attributes('disabled')).toBeUndefined()
  })

  it('change：selectGame 持久化并 emit 给父组件', async () => {
    const { GameSelector, useGameList } = await fresh()
    const w = mount(GameSelector)
    await flushPromises()

    await w.find('select').setValue('g2')
    expect(w.emitted('change')).toEqual([['g2']])
    expect(useGameList().currentGameId.value).toBe('g2')
    expect(localStorage.getItem('oddsmaker.selectedGameId')).toBe('g2')
  })
})
