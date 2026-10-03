import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { settle } from '../helpers/settle.js'

/**
 * views/DashboardView.vue：三个首屏请求独立落定——任一被拒（如演示 VIEWER
 * 缺 user:read → 403）时其余统计卡片与最近活动仍渲染，不再一挂全挂。
 */
vi.mock('@/services/api', () => ({ default: { get: vi.fn() } }))

import api from '@/services/api'
import DashboardView from '@/views/DashboardView.vue'

const ACTIVITY = {
  id: 'log_1',
  username: 'alice',
  action: 'CREATE',
  resourceType: 'GAME',
  createdAt: '2026-10-01T08:00:00'
}

/** 按标签取统计卡片数值（值紧跟在标签 <p> 之后） */
function statValue(w, label) {
  const ps = w.findAll('p')
  const i = ps.findIndex((p) => p.text() === label)
  return ps[i + 1].text()
}

async function mountView() {
  const w = mount(DashboardView)
  await settle()
  return w
}

beforeEach(() => {
  vi.clearAllMocks()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('DashboardView', () => {
  it('全成功：统计卡片渲染真实数值、最近活动非空态、三请求各打一次', async () => {
    api.get.mockImplementation((url) => {
      if (url === '/api/games/statistics') return Promise.resolve({ data: { totalGames: 1, liveGames: 0 } })
      if (url === '/api/users/statistics') return Promise.resolve({ data: { totalUsers: 42 } })
      if (url === '/api/audit-logs?size=10') return Promise.resolve({ data: { content: [ACTIVITY] } })
      return Promise.reject(new Error('unexpected url: ' + url))
    })
    const w = await mountView()

    expect(statValue(w, '总游戏数')).toBe('1')
    expect(statValue(w, '活跃游戏')).toBe('0')
    expect(statValue(w, '总用户数')).toBe('42')
    expect(w.text()).toContain('alice')
    expect(w.text()).toContain('CREATE')
    expect(w.text()).not.toContain('暂无活动记录')
    expect(api.get).toHaveBeenCalledTimes(3)
  })

  it('users/statistics 被拒（VIEWER 无 user:read）：游戏卡片与最近活动不受拖累，用户卡保持 0', async () => {
    api.get.mockImplementation((url) => {
      if (url === '/api/games/statistics') return Promise.resolve({ data: { totalGames: 3, liveGames: 2 } })
      if (url === '/api/users/statistics') return Promise.reject(new Error('403 Forbidden'))
      if (url === '/api/audit-logs?size=10') return Promise.resolve({ data: { content: [ACTIVITY] } })
      return Promise.reject(new Error('unexpected url: ' + url))
    })
    const w = await mountView()

    expect(statValue(w, '总游戏数')).toBe('3')
    expect(statValue(w, '活跃游戏')).toBe('2')
    expect(statValue(w, '总用户数')).toBe('0')
    expect(w.text()).toContain('alice')
    expect(w.text()).not.toContain('暂无活动记录')
  })

  it('全被拒（网络/权限全挂）：整屏落空态而非白屏，loading 结束', async () => {
    api.get.mockRejectedValue(new Error('network down'))
    const w = await mountView()

    expect(statValue(w, '总游戏数')).toBe('0')
    expect(w.text()).toContain('暂无活动记录')
    expect(api.get).toHaveBeenCalledTimes(3)
  })
})
