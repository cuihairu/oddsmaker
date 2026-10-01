import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'

/** TrendChart 打桩：具名 + 同 props，findComponent 按定义精确匹配并透传断言 props */
const TrendChartStub = defineComponent({
  name: 'TrendChart',
  props: { labels: Array, series: Array, percent: Boolean, height: Number },
  setup: () => () => h('div', { class: 'trend-chart-stub' })
})

/**
 * views/DashboardsView.vue：自定义仪表盘（布局 CRUD + 4 源 × 4 类型 widget 数据面）。
 *
 * - 未选游戏 load 早退；games 拉取后自动选中触发 watch(currentGameId) 首次加载
 * - 4 类型（kpi/line/bar/table）× 4 源（online/retention/payment/crash）展示模型与
 *   兜底（?? 0 / ?? 5 / pct '—' / slice 截断 / params.days || 90）
 * - 数据缓存按 source+params 去重（cache-hit 与 dataLoading 竞态双臂）、错误臂
 *   （后端 message / 兜底文案 / 未知数据源 / available:false）
 * - 仪表盘 CRUD：新建（prompt 取消/成功/失败）、删除（confirm 取消/成功/失败）、
 *   保存（dirty 门控/成功/失败）、widget 增删、坏 layout JSON、切换仪表盘
 * - 展示模型矩阵：line/bar/table × 4 源全组合（空 payload 的 || 兜底、nullish 值
 *   ?? 0 兜底）、rateOf 三步转化率、弹层全字段 v-model 联动、widgetTitle 源名回落
 * - 不可达臂（记账不硬凑，5 行 + 6 臂）：kpiValue switch default（未知源数据永不
 *   入缓存，模板错误分支先短路）；line/bar/table 各自链尾回落与 payment/crash 尾
 *   if 的 false 臂（已知源均在更早分支 return，未知源先行抛错）；rateOf 的
 *   return null 与 retained30-if false 臂（调用点只喂 firstPay/secondPay/retained30）；
 *   barMax 的 model||[] 空臂（唯一调用点有类型真值守卫）
 *
 * 组件依赖 useGameList 模块单例——每用例 resetModules + 先设 localStorage 再动态
 * import 视图，保证单例从干净状态起步；GameSelector/TrendChart 打桩隔离。
 *
 * 时序口径（本环境 vitest/vite 转换会把 `dataCache.value[key] = await …` 的赋值目标
 * 对象引用提前到 await 之前捕获；真实浏览器原生 async 在 await 后求值，无此现象）：
 * load() 首轮把 activeId 从 null 切到首个仪表盘，watcher 与直调各跑一次 applyActive
 * （后者清空 dataCache），首轮 fetch 的赋值会落到被替换前的旧对象上。数据着陆类用例
 * 以「启动空仪表盘 → settle 后手动切换」起步（mountDash），从无二次清空的
 * applyActive 发起拉取；CRUD/错误臂类用例不断言着陆值，直接交互即可。
 */
vi.mock('@/services/api', () => ({ default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() } }))

const STORAGE_KEY = 'oddsmaker.selectedGameId'

const ok = (data) => new Promise((res) => setTimeout(() => res({ data }), 0))
const bad = (err) => new Promise((_, rej) => setTimeout(() => rej(err), 0))

// ---- 数据面 fixture：4 源各一套，online/crash/retention 支持按入参分化（验证 params 透传与兜底）----
const ONLINE = {
  online: 42,
  minutes: 10,
  trend: [{ ts: '2026-09-30T10:01:00', online: 5 }],
  byPlatform: [{ key: 'iOS', online: 30 }, { key: 'Android', online: 12 }],
  byAppVersion: [{ key: '1.2.0', online: 20 }]
}
const RETENTION = {
  summary: { avgD1Rate: 0.4, cohorts: 3 },
  points: [{ cohort: '2026-09-01', newUsers: 100, d1Rate: 0.4 }]
}
const PAYMENT = {
  funnel: {
    registered: 1000, firstPay: 100, secondPay: 30, retained30: 50,
    firstPayRate: 0.1, secondPayRate: 0.3, retained30Rate: 0.5
  }
}
const CRASH = { points: [{ date: '2026-09-29', crashes: 7, affectedDevices: 3 }] }

function deferred() {
  let resolve
  const p = new Promise((res) => { resolve = res })
  return { p, resolve }
}

async function fresh({
  selectedGame = 'g1',
  games = [{ id: 'g1' }],
  dashboards = [],
  dashboardError = null,
  online = ONLINE,
  retention = RETENTION,
  payment = PAYMENT,
  crash = CRASH,
  failOnline = null,
  failCrash = null,
  slowSources = []
} = {}) {
  vi.resetModules()
  localStorage.clear()
  if (selectedGame) localStorage.setItem(STORAGE_KEY, selectedGame)

  const api = (await import('@/services/api')).default
  const pending = {}
  api.get.mockImplementation((url, cfg = {}) => {
    if (url === '/api/games') return ok({ content: games })
    if (url.endsWith('/dashboards')) {
      return dashboardError ? bad(dashboardError) : ok(dashboards)
    }
    const src = url.includes('/online-metrics') ? ['online', online]
      : url.includes('/retention-metrics') ? ['retention', retention]
      : url.includes('/payment-metrics') ? ['payment', payment]
      : url.includes('/crash-metrics') ? ['crash', crash]
      : null
    if (src) {
      const [name, payload] = src
      if (failOnline && name === 'online') return bad(failOnline)
      if (failCrash && name === 'crash') return bad(failCrash)
      const data = typeof payload === 'function' ? payload(cfg) : payload
      if (slowSources.includes(name)) {
        pending[name] = deferred()
        return pending[name].p.then(() => ({ data }))
      }
      return ok(data)
    }
    return ok({})
  })

  const DashboardsView = (await import('@/views/DashboardsView.vue')).default
  return { api, DashboardsView, pending }
}

const settle = async () => {
  await flushPromises()
  await flushPromises()
  await flushPromises()
}

function mountView(DashboardsView) {
  const w = mount(DashboardsView, { global: { stubs: { GameSelector: true, TrendChart: TrendChartStub } } })
  return settle().then(() => w)
}

/** 启动位空仪表盘：数据着陆类用例先落它，绕开首轮 activeId 切换的双 applyActive 竞态 */
const BOOT = { id: 'boot', name: '启动位', layout: JSON.stringify({ widgets: [] }) }

async function mountDash(ctx, target) {
  const w = await mountView(ctx.DashboardsView)
  await w.find('select').setValue(target.id)
  await settle()
  return w
}

const findBtn = (w, label) => w.findAll('button').find((b) => b.text() === label)
const cardFor = (w, title) => w.findAll('.card').find((c) => c.text().includes(title))
const callsOf = (api, frag) => api.get.mock.calls.filter((c) => c[0].includes(frag))

const W_ONLINE_KPI = { id: 'w1', type: 'kpi', source: 'online-overview', title: '当前在线', span: 3, params: { environment: 'prod', minutes: 10 } }
const W_RETENTION_LINE = { id: 'w2', type: 'line', source: 'retention-trend', title: '', span: 6, params: { environment: 'prod', days: 30, granularity: 'day' } }
const W_PAYMENT_BAR = { id: 'w3', type: 'bar', source: 'payment-funnel', title: '付费', span: 6, params: { days: 90 } }
const W_CRASH_TABLE = { id: 'w4', type: 'table', source: 'crash-trend', title: 'Crash', span: 12, params: { environment: 'prod', days: 14 } }
const LAYOUT4 = JSON.stringify({ widgets: [W_ONLINE_KPI, W_RETENTION_LINE, W_PAYMENT_BAR, W_CRASH_TABLE] })
const DASH4 = { id: 'd1', name: '主面板', layout: LAYOUT4 }

beforeEach(() => {
  vi.clearAllMocks()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('DashboardsView', () => {
  it('未选游戏：load 早退不发仪表盘请求；games 拉取后自动选中触发 watch 首次加载', async () => {
    const { api, DashboardsView } = await fresh({
      selectedGame: null,
      dashboards: [{ id: 'd1', name: '主面板', layout: JSON.stringify({ widgets: [] }) }]
    })
    const w = await mountView(DashboardsView)
    await settle()

    expect(callsOf(api, '/dashboards')).toHaveLength(1) // 早退那轮不发，自动选中后 watch 触发
    expect(w.text()).toContain('主面板')
    expect(w.text()).toContain('空仪表盘')
  })

  it('4 类型 × 各源渲染：KPI/折线/柱状/表格与 span 栅格类', async () => {
    const ctx = await fresh({ dashboards: [BOOT, DASH4] })
    const api = ctx.api
    const w = await mountDash(ctx, DASH4)

    // 源请求按 widget 逐个发出，参数来自 widgetParams
    expect(callsOf(api, '/online-metrics')[0]).toEqual([
      '/api/online-metrics/g1', { params: { environment: 'prod', minutes: 10 } }
    ])
    expect(callsOf(api, '/retention-metrics')[0]).toEqual([
      '/api/retention-metrics/g1/trend', { params: { environment: 'prod', days: 30, granularity: 'day' } }
    ])
    expect(callsOf(api, '/payment-metrics')[0]).toEqual([
      '/api/payment-metrics/g1/funnel', { params: { days: 90 } }
    ])
    expect(callsOf(api, '/crash-metrics')[0]).toEqual([
      '/api/crash-metrics/g1/trend', { params: { environment: 'prod', days: 14 } }
    ])

    const text = w.text()
    expect(text).toContain('当前在线')
    expect(text).toContain('42') // KPI 数值
    expect(text).toContain('近 10 分钟')
    expect(text).toContain('留存趋势') // 空 title 回落 SOURCE_LABELS
    // pct 文本格式化在 KPI 用例覆盖；此处折线走 TrendChart stub，数值经 props 断言（下方 0.4）
    expect(text).toContain('付费')

    // 折线：真实模型经 stub 透传（retention 截尾 30 + cohort 切片 + percent）
    const chart = w.findComponent(TrendChartStub)
    expect(chart.props('labels')).toEqual(['09-01'])
    expect(chart.props('series')).toEqual([{ name: 'D1 留存', color: '#10b981', values: [0.4] }])
    expect(chart.props('percent')).toBe(true)

    // 柱状：漏斗 4 步 + 满格宽度按 barMax 归一
    expect(text).toContain('注册')
    expect(text).toMatch(/1[,.]?000/)
    const bars = w.findAll('.bg-primary-500')
    expect(bars).toHaveLength(4)
    expect(bars[0].attributes('style')).toContain('width: 100%')

    // 表格：crash 明细
    expect(text).toContain('影响设备')
    expect(text).toContain('2026-09-29')
    expect(text).toContain('7 次')
    expect(text).toContain('3 设备')

    // span 白名单类
    const cards = w.findAll('.card')
    expect(cards.some((c) => c.classes().includes('md:col-span-3'))).toBe(true)
    expect(cards.some((c) => c.classes().includes('md:col-span-12'))).toBe(true)
  })

  it('KPI 各源展示模型：payment/crash 正常值、retention、无 summary 时 pct "—"、crash 空点、online 缺参兜底', async () => {
    const KPI_WALL = {
      id: 'd1', name: 'KPI 墙', layout: JSON.stringify({ widgets: [
        { id: 'k1', type: 'kpi', source: 'online-overview', title: '', span: 3, params: {} },
        { id: 'k2', type: 'kpi', source: 'retention-trend', title: '', span: 3, params: { days: 30 } },
        { id: 'k3', type: 'kpi', source: 'retention-trend', title: '', span: 3, params: { days: 7 } },
        { id: 'k4', type: 'kpi', source: 'payment-funnel', title: '', span: 3, params: {} },
        { id: 'k5', type: 'kpi', source: 'crash-trend', title: '', span: 3, params: { days: 14 } },
        { id: 'k6', type: 'kpi', source: 'crash-trend', title: '', span: 3, params: { days: 21 } }
      ] })
    }
    const ctx = await fresh({
      dashboards: [BOOT, KPI_WALL],
      retention: (cfg) => (cfg?.params?.days === 30 ? RETENTION : {}),
      crash: (cfg) => (cfg?.params?.days === 14 ? CRASH : {}),
      online: (cfg) => (cfg?.params?.minutes ? ONLINE : {})
    })
    const w = await mountDash(ctx, KPI_WALL)

    const text = w.text()
    expect(text).toContain('40.0%')
    expect(text).toContain('3 个 cohort')
    expect(text).toContain('—') // k3 无 summary → pct null 臂
    expect(text).toContain('0 个 cohort')
    expect(text).toContain('10.0%') // payment firstPayRate
    expect(text).toContain('注册→首付 转化')
    expect(text).toContain('7') // crash 最新点
    expect(text).toContain('最新 2026-09-29')
    expect(text).toContain('0次') // k6 空点兜底 ?? 0
    expect(text).toContain('近 5 分钟') // k1 d.minutes 缺 → params 缺 → 5
  })

  it('available:false：ClickHouse 未配置横幅短路 KPI 数字区（v-else-if 先于 kpi 分支命中）', async () => {
    const DASH = { id: 'd1', name: '主面板', layout: JSON.stringify({ widgets: [W_ONLINE_KPI] }) }
    const ctx = await fresh({
      dashboards: [BOOT, DASH],
      online: { available: false }
    })
    const w = await mountDash(ctx, DASH)
    expect(w.text()).toContain('ClickHouse 未配置，数据不可用')
    // 横幅分支命中后 KPI 分支不渲染：'—' 占位与数值/单位均不出现
    expect(w.text()).not.toContain('—')
    expect(w.text()).not.toContain('42')
  })

  it('仪表盘列表失败臂：后端 message 与无 response 兜底', async () => {
    const { DashboardsView } = await fresh({
      dashboardError: { response: { data: { message: '无权限' } } }
    })
    const w = await mountView(DashboardsView)
    expect(w.text()).toContain('无权限')
    await w.find('.text-red-500').trigger('click') // 错误横幅可关闭
    expect(w.text()).not.toContain('无权限')

    const { DashboardsView: V2 } = await fresh({ dashboardError: new Error('x') })
    const w2 = await mountView(V2)
    expect(w2.text()).toContain('加载仪表盘列表失败')
  })

  it('widget 数据失败臂：后端 message 与兜底文案各归其位', async () => {
    const { DashboardsView } = await fresh({
      dashboards: [{
        id: 'd1', name: '主面板', layout: JSON.stringify({ widgets: [
          { id: 'e1', type: 'kpi', source: 'online-overview', title: '在线', span: 6, params: { minutes: 10 } },
          { id: 'e2', type: 'kpi', source: 'crash-trend', title: 'Crash', span: 6, params: { days: 14 } }
        ] })
      }],
      failOnline: { response: { data: { message: 'ck 炸了' } } },
      failCrash: new Error('x')
    })
    const w = await mountView(DashboardsView)
    expect(w.text()).toContain('ck 炸了')
    expect(w.text()).toContain('数据加载失败')
  })

  it('未知数据源：fetchSource default 臂抛错 → 兜底文案，不发请求', async () => {
    const { api, DashboardsView } = await fresh({
      dashboards: [{
        id: 'd1', name: '主面板', layout: JSON.stringify({ widgets: [
          { id: 'u1', type: 'bar', source: 'weird-source', title: '怪源', span: 6 }
        ] })
      }]
    })
    const w = await mountView(DashboardsView)
    expect(w.text()).toContain('数据加载失败')
    expect(callsOf(api, 'weird')).toHaveLength(0)
  })

  it('无数据/未知类型：bar 空模型与 pie 未知类型均落「暂无数据」；span 越界回落半宽；缺 id 自动补', async () => {
    const { api, DashboardsView } = await fresh({
      dashboards: [{
        id: 'd1', name: '主面板', layout: JSON.stringify({ widgets: [
          { id: 'n1', type: 'bar', source: 'online-overview', title: '在线', span: 6, params: { minutes: 10 } },
          { type: 'pie', source: 'online-overview', title: '饼图', span: 99, params: { minutes: 10 } }
        ] })
      }],
      online: {}
    })
    const w = await mountView(DashboardsView)
    expect(w.text()).toContain('暂无数据')
    expect(w.text()).toContain('饼图')
    // span 99 不在白名单 → 回落 md:col-span-6
    expect(w.findAll('.card').some((c) => c.classes().includes('md:col-span-6'))).toBe(true)
    // online 源只拉一次（同 source+params 去重）
    expect(callsOf(api, '/online-metrics')).toHaveLength(1)
  })

  it('坏 layout JSON：parseLayout catch 回落空数组 → 空仪表盘引导', async () => {
    const { DashboardsView } = await fresh({
      dashboards: [{ id: 'd1', name: '坏面板', layout: '{not-json' }]
    })
    const w = await mountView(DashboardsView)
    expect(w.text()).toContain('空仪表盘')
  })

  it('切换仪表盘：watch(activeId) → applyActive 换 widget 并清缓存重拉', async () => {
    const DASH_B = { id: 'd2', name: '备用', layout: JSON.stringify({ widgets: [W_ONLINE_KPI] }) }
    const { api, DashboardsView } = await fresh({ dashboards: [DASH4, DASH_B] })
    const w = await mountView(DashboardsView)
    expect(w.text()).toContain('付费')
    expect(callsOf(api, '/online-metrics')).toHaveLength(1)

    await w.find('select').setValue('d2')
    await settle()
    expect(w.text()).not.toContain('付费')
    expect(callsOf(api, '/online-metrics')).toHaveLength(2) // 缓存清空后重拉
  })

  it('新建：prompt 命名成功 → POST 默认布局并激活新仪表盘', async () => {
    vi.stubGlobal('prompt', vi.fn(() => '新面板'))
    const { api, DashboardsView } = await fresh({
      dashboards: [{ id: 'd1', name: '主面板', layout: JSON.stringify({ widgets: [] }) }]
    })
    api.post.mockResolvedValue({
      data: { id: 'd2', name: '新面板', layout: JSON.stringify({ widgets: [W_ONLINE_KPI] }) }
    })
    const w = await mountView(DashboardsView)

    await findBtn(w, '新建').trigger('click')
    await settle()

    expect(api.post).toHaveBeenCalledTimes(1)
    const [url, body] = api.post.mock.calls[0]
    expect(url).toBe('/api/games/g1/dashboards')
    expect(body.name).toBe('新面板')
    const layout = JSON.parse(body.layout)
    expect(layout.widgets).toHaveLength(1)
    expect(layout.widgets[0]).toMatchObject({ type: 'kpi', source: 'online-overview', title: '当前在线', span: 3, params: { minutes: 10 } })

    expect(w.text()).toContain('新面板')
    expect(w.text()).toContain('当前在线')
    expect(w.text()).not.toContain('空仪表盘')
  })

  it('新建：prompt 取消不发请求', async () => {
    vi.stubGlobal('prompt', vi.fn(() => null))
    const { api, DashboardsView } = await fresh({ dashboards: [] })
    const w = await mountView(DashboardsView)

    await findBtn(w, '新建').trigger('click')
    await settle()
    expect(api.post).not.toHaveBeenCalled()
  })

  it('新建失败：错误横幅展示后端 message', async () => {
    vi.stubGlobal('prompt', vi.fn(() => '重复名'))
    const { api, DashboardsView } = await fresh({ dashboards: [] })
    api.post.mockRejectedValue({ response: { data: { message: '名称已存在' } } })
    const w = await mountView(DashboardsView)

    await findBtn(w, '新建').trigger('click')
    await settle()
    expect(w.text()).toContain('名称已存在')
  })

  it('删除：confirm 确认 → DELETE 后回落到剩余仪表盘并出成功横幅（横幅可关闭）', async () => {
    vi.stubGlobal('confirm', vi.fn(() => true))
    const DASH_B = { id: 'd2', name: '备用', layout: JSON.stringify({ widgets: [] }) }
    const { api, DashboardsView } = await fresh({
      dashboards: [{ id: 'd1', name: '主面板', layout: LAYOUT4 }, DASH_B]
    })
    api.delete.mockResolvedValue({ data: {} })
    const w = await mountView(DashboardsView)

    await findBtn(w, '删除').trigger('click')
    await settle()

    expect(api.delete).toHaveBeenCalledWith('/api/dashboards/d1')
    expect(w.text()).toContain('仪表盘「主面板」已删除')
    expect(w.text()).toContain('空仪表盘') // 回落到备用的空布局

    await w.find('.text-green-500').trigger('click')
    expect(w.text()).not.toContain('已删除')
  })

  it('删除：confirm 取消不发请求', async () => {
    vi.stubGlobal('confirm', vi.fn(() => false))
    const { api, DashboardsView } = await fresh({ dashboards: [{ id: 'd1', name: '主面板', layout: LAYOUT4 }] })
    const w = await mountView(DashboardsView)

    await findBtn(w, '删除').trigger('click')
    await settle()
    expect(api.delete).not.toHaveBeenCalled()
  })

  it('删除失败：错误横幅展示后端 message', async () => {
    vi.stubGlobal('confirm', vi.fn(() => true))
    const { api, DashboardsView } = await fresh({ dashboards: [{ id: 'd1', name: '主面板', layout: LAYOUT4 }] })
    api.delete.mockRejectedValue({ response: { data: { message: '被引用中' } } })
    const w = await mountView(DashboardsView)

    await findBtn(w, '删除').trigger('click')
    await settle()
    expect(w.text()).toContain('被引用中')
  })

  it('widget 移除 → dirty 点亮保存按钮 → save PUT 布局成功（横幅可关闭）', async () => {
    const { api, DashboardsView } = await fresh({ dashboards: [DASH4] })
    api.put.mockResolvedValue({ data: { layout: '{}' } })
    const w = await mountView(DashboardsView)
    expect(findBtn(w, '已保存')).toBeTruthy()

    await w.find('button[title="移除"]').trigger('click')
    expect(w.findAll('button[title="移除"]')).toHaveLength(3)
    expect(findBtn(w, '保存布局')).toBeTruthy() // dirty

    await findBtn(w, '保存布局').trigger('click')
    await settle()

    expect(api.put).toHaveBeenCalledTimes(1)
    const [url, body] = api.put.mock.calls[0]
    expect(url).toBe('/api/dashboards/d1')
    const saved = JSON.parse(body.layout).widgets
    expect(saved.map((x) => x.id)).toEqual(['w2', 'w3', 'w4'])
    expect(findBtn(w, '已保存')).toBeTruthy()
    expect(w.text()).toContain('布局已保存')

    await w.find('.text-green-500').trigger('click')
    expect(w.text()).not.toContain('布局已保存')
  })

  it('save 失败：错误横幅展示后端 message；dirty 保持可重试', async () => {
    const { api, DashboardsView } = await fresh({
      dashboards: [{ id: 'd1', name: '主面板', layout: LAYOUT4 }]
    })
    api.put.mockRejectedValue({ response: { data: { message: '版本冲突' } } })
    const w = await mountView(DashboardsView)

    await w.find('button[title="移除"]').trigger('click') // 先制造 dirty
    await findBtn(w, '保存布局').trigger('click')
    await settle()

    expect(w.text()).toContain('版本冲突')
    expect(findBtn(w, '保存布局')).toBeTruthy() // dirty 保持，可重试
  })

  it('添加 widget（online-overview 默认表单）：参数拼装、弹层关闭、dirty 点亮', async () => {
    const { api, DashboardsView } = await fresh({
      dashboards: [{ id: 'd1', name: '主面板', layout: JSON.stringify({ widgets: [] }) }]
    })
    const w = await mountView(DashboardsView)
    expect(w.text()).toContain('空仪表盘')

    await findBtn(w, '+ Widget').trigger('click')
    expect(w.text()).toContain('添加 Widget')

    await findBtn(w, '添加').trigger('click')
    await settle()

    expect(w.text()).not.toContain('添加 Widget') // 弹层关闭
    expect(w.text()).not.toContain('空仪表盘')
    expect(w.text()).toContain('实时在线') // 空 title 回落 SOURCE_LABELS
    expect(callsOf(api, '/online-metrics')[0]).toEqual([
      '/api/online-metrics/g1', { params: { environment: 'prod', minutes: 10 } }
    ])
    expect(findBtn(w, '保存布局')).toBeTruthy() // dirty

    // 再次打开后取消可关闭弹层
    await findBtn(w, '+ Widget').trigger('click')
    await findBtn(w, '取消').trigger('click')
    expect(w.text()).not.toContain('添加 Widget')
  })

  it('添加 widget（retention-trend）：days/granularity 分支拼装并发起拉取', async () => {
    const { api, DashboardsView } = await fresh({
      dashboards: [{ id: 'd1', name: '主面板', layout: JSON.stringify({ widgets: [] }) }]
    })
    const w = await mountView(DashboardsView)

    await findBtn(w, '+ Widget').trigger('click')
    await w.find('.fixed select').setValue('retention-trend') // 弹层内数据源下拉
    await findBtn(w, '添加').trigger('click')
    await settle()

    expect(callsOf(api, '/retention-metrics')[0]).toEqual([
      '/api/retention-metrics/g1/trend', { params: { environment: 'prod', days: 30, granularity: 'day' } }
    ])
    expect(w.text()).toContain('留存趋势')
  })

  it('line 矩阵：4 源折线模型经 stub 透传；空 payload 走 || [] 兜底', async () => {
    const W = (id, source, params, title) => ({ id, type: 'line', source, title, span: 6, params })
    const DASH = { id: 'd1', name: '折线墙', layout: JSON.stringify({ widgets: [
      W('l1', 'online-overview', { environment: 'prod', minutes: 10 }, 'L-在线'),
      W('l2', 'retention-trend', { environment: 'prod', days: 30, granularity: 'day' }, 'L-留存'),
      W('l3', 'payment-funnel', { days: 90 }, 'L-付费'),
      W('l4', 'crash-trend', { environment: 'prod', days: 14 }, 'L-Crash'),
      W('l5', 'retention-trend', { environment: 'prod', days: 7, granularity: 'day' }, 'L-留存空'),
      W('l6', 'crash-trend', { environment: 'prod', days: 21 }, 'L-Crash空'),
      W('l7', 'payment-funnel', { days: 60 }, 'L-付费空'),
      W('l8', 'online-overview', { environment: 'prod', minutes: 5 }, 'L-在线空')
    ] }) }
    const ctx = await fresh({
      dashboards: [BOOT, DASH],
      online: (cfg) => (cfg?.params?.minutes === 10 ? ONLINE : {}),
      retention: (cfg) => (cfg?.params?.days === 30 ? RETENTION : {}),
      crash: (cfg) => (cfg?.params?.days === 14 ? CRASH : {}),
      payment: (cfg) => (cfg?.params?.days === 90 ? PAYMENT : {})
    })
    const w = await mountDash(ctx, DASH)

    const charts = w.findAllComponents(TrendChartStub)
    expect(charts).toHaveLength(8)
    expect(charts[0].props('labels')).toEqual(['10:01'])
    expect(charts[0].props('series')).toEqual([{ name: '在线', color: '#3b82f6', values: [5] }])
    expect(charts[0].props('percent')).toBe(false)
    expect(charts[1].props('labels')).toEqual(['09-01'])
    expect(charts[1].props('series')).toEqual([{ name: 'D1 留存', color: '#10b981', values: [0.4] }])
    expect(charts[1].props('percent')).toBe(true)
    expect(charts[2].props('labels')).toEqual(['注册', '首付', '二付'])
    expect(charts[2].props('series')).toEqual([{ name: '人数', color: '#8b5cf6', values: [1000, 100, 30] }])
    expect(charts[3].props('labels')).toEqual(['09-29'])
    expect(charts[3].props('series')).toEqual([{ name: 'Crash 次数', color: '#ef4444', values: [7] }])
    // 空 payload：pts 兜底 []，模型仍成立（labels/values 空）
    for (const i of [4, 5]) {
      expect(charts[i].props('labels')).toEqual([])
      expect(charts[i].props('series')[0].values).toEqual([])
    }
    // 空 payment：funnel || {} 仍有 3 个标签，值 nullish（undefined）
    expect(charts[6].props('labels')).toEqual(['注册', '首付', '二付'])
    expect(charts[6].props('series')[0].values).toHaveLength(3)
    for (const v of charts[6].props('series')[0].values) expect(v).toBeUndefined()
    // 空 online：trend || [] 兜底
    expect(charts[7].props('labels')).toEqual([])
    expect(charts[7].props('series')).toEqual([{ name: '在线', color: '#3b82f6', values: [] }])
  })

  it('bar 矩阵：4 源柱状模型；空 payload 与 nullish 值按 0 兜底', async () => {
    const W = (id, source, params, title) => ({ id, type: 'bar', source, title, span: 6, params })
    const DASH = { id: 'd1', name: '柱状墙', layout: JSON.stringify({ widgets: [
      W('b1', 'online-overview', { environment: 'prod', minutes: 10 }, 'B-在线'),
      W('b2', 'retention-trend', { environment: 'prod', days: 30, granularity: 'day' }, 'B-留存'),
      W('b3', 'crash-trend', { environment: 'prod', days: 14 }, 'B-Crash'),
      W('b4', 'payment-funnel', { days: 60 }, 'B-付费空'),
      W('b5', 'online-overview', { environment: 'prod', minutes: 5 }, 'B-在线空'),
      W('b6', 'retention-trend', { environment: 'prod', days: 7, granularity: 'day' }, 'B-留存空'),
      W('b7', 'crash-trend', { environment: 'prod', days: 21 }, 'B-Crash空')
    ] }) }
    const ctx = await fresh({
      dashboards: [BOOT, DASH],
      online: (cfg) => (cfg?.params?.minutes === 10 ? ONLINE : {}),
      retention: (cfg) => (cfg?.params?.days === 30 ? RETENTION : {}),
      crash: (cfg) => (cfg?.params?.days === 14 ? CRASH : {}),
      payment: {}
    })
    const w = await mountDash(ctx, DASH)

    // 正常模型：柱数、标签、数值（barMax 归一）
    const b1 = cardFor(w, 'B-在线')
    expect(b1.findAll('.bg-primary-500')).toHaveLength(2)
    expect(b1.text()).toContain('iOS')
    expect(b1.findAll('span.font-mono').map((s) => s.text())).toEqual(['30', '12'])
    expect(cardFor(w, 'B-留存').findAll('.bg-primary-500')).toHaveLength(1)
    expect(cardFor(w, 'B-留存').text()).toContain('100')
    expect(cardFor(w, 'B-Crash').findAll('.bg-primary-500')).toHaveLength(1)
    expect(cardFor(w, 'B-Crash').text()).toContain('3')
    // 付费空 funnel：4 行值全 nullish → 数值 '0'、宽度 0%（|| 0 与 ?? 0 双兜底臂）
    const b4 = cardFor(w, 'B-付费空')
    expect(b4.findAll('.bg-primary-500')).toHaveLength(4)
    expect(b4.findAll('span.font-mono').map((s) => s.text())).toEqual(['0', '0', '0', '0'])
    expect(b4.findAll('.bg-primary-500')[0].attributes('style')).toContain('width: 0%')
    // 空 payload：模型空数组，零柱
    for (const t of ['B-在线空', 'B-留存空', 'B-Crash空']) {
      expect(cardFor(w, t).findAll('.bg-primary-500')).toHaveLength(0)
    }
  })

  it('table 矩阵：4 源表格模型、rateOf 转化率与 ?? 0 兜底', async () => {
    const W = (id, source, params, title) => ({ id, type: 'table', source, title, span: 6, params })
    const RETENTION_PARTIAL = {
      summary: { avgD1Rate: 0.33, cohorts: 2 },
      points: [
        { cohort: '2026-09-01', newUsers: 100, d1Rate: 0.4 },
        { cohort: '2026-09-02', d1Rate: 0.25 } // 缺 newUsers → '0 新增'
      ]
    }
    const PAYMENT_PARTIAL = { funnel: { registered: 1000, firstPay: 100, firstPayRate: 0.1 } } // 缺二付/留存30
    const DASH = { id: 'd1', name: '表格墙', layout: JSON.stringify({ widgets: [
      W('t1', 'online-overview', { environment: 'prod', minutes: 10 }, 'T-在线'),
      W('t2', 'retention-trend', { environment: 'prod', days: 30, granularity: 'day' }, 'T-留存'),
      W('t3', 'payment-funnel', { days: 90 }, 'T-付费'),
      W('t4', 'payment-funnel', { days: 120 }, 'T-付费残缺'),
      W('t5', 'online-overview', { environment: 'prod', minutes: 5 }, 'T-在线空'),
      W('t6', 'retention-trend', { environment: 'prod', days: 7, granularity: 'day' }, 'T-留存空'),
      W('t7', 'payment-funnel', { days: 60 }, 'T-付费空'),
      W('t8', 'crash-trend', { environment: 'prod', days: 14 }, 'T-Crash残缺'),
      W('t9', 'crash-trend', { environment: 'prod', days: 21 }, 'T-Crash空')
    ] }) }
    const ctx = await fresh({
      dashboards: [BOOT, DASH],
      online: (cfg) => (cfg?.params?.minutes === 10 ? ONLINE : {}),
      retention: (cfg) => (cfg?.params?.days === 30 ? RETENTION_PARTIAL : {}),
      payment: (cfg) => (cfg?.params?.days === 90 ? PAYMENT : cfg?.params?.days === 120 ? PAYMENT_PARTIAL : {}),
      crash: (cfg) => (cfg?.params?.days === 14 ? { points: [{ date: '2026-09-29' }] } : {})
    })
    const w = await mountDash(ctx, DASH)

    const rowsOf = (title) => cardFor(w, title).findAll('tbody tr').map((r) => r.text())
    expect(rowsOf('T-在线')).toEqual(['平台iOS30', '平台Android12', '版本1.2.020'])
    expect(rowsOf('T-留存')).toEqual(['2026-09-01100 新增40.0%', '2026-09-020 新增25.0%'])
    expect(rowsOf('T-付费')).toEqual(['注册1,000—', '首付10010.0%', '二付3030.0%', '留存305050.0%'])
    expect(rowsOf('T-付费残缺')).toEqual(['注册1,000—', '首付10010.0%', '二付0—', '留存300—'])
    // 空 payload：online/retention 走 || [] 零行；payment 走 funnel || {} 仍有 4 行全兜底值
    expect(cardFor(w, 'T-在线空').findAll('tbody tr')).toHaveLength(0)
    expect(cardFor(w, 'T-留存空').findAll('tbody tr')).toHaveLength(0)
    expect(rowsOf('T-付费空')).toEqual(['注册0—', '首付0—', '二付0—', '留存300—'])
    // crash 残缺点位：crashes/affectedDevices 缺失均按 0 兜底；空 points 零行
    expect(rowsOf('T-Crash残缺')).toEqual(['2026-09-290 次0 设备'])
    expect(cardFor(w, 'T-Crash空').findAll('tbody tr')).toHaveLength(0)
  })

  it('未知源：标题回落源名（widgetTitle 第三臂）、fetchSource 先抛不发请求', async () => {
    const { api, DashboardsView } = await fresh({
      dashboards: [{ id: 'd1', name: '主面板', layout: JSON.stringify({ widgets: [
        { id: 'x1', type: 'kpi', source: 'weird-source', title: '', span: 6 }
      ] }) }]
    })
    const w = await mountView(DashboardsView)

    expect(w.text()).toContain('weird-source') // 空 title → SOURCE_LABELS 未命中 → 源名兜底
    expect(w.text()).toContain('数据加载失败')
    for (const frag of ['/online-metrics', '/retention-metrics', '/payment-metrics', '/crash-metrics']) {
      expect(callsOf(api, frag)).toHaveLength(0) // fetchSource default 先抛，不发请求
    }
  })

  it('弹层全字段：遮罩点击关闭、type/title/span/env/days/minutes v-model 联动', async () => {
    const { api, DashboardsView } = await fresh({
      dashboards: [{ id: 'd1', name: '主面板', layout: JSON.stringify({ widgets: [] }) }]
    })
    const w = await mountView(DashboardsView)

    await findBtn(w, '+ Widget').trigger('click')
    await w.find('.fixed .absolute').trigger('click') // 遮罩关闭
    expect(w.text()).not.toContain('添加 Widget')

    await findBtn(w, '+ Widget').trigger('click')
    const [srcSel, typeSel, spanSel, envSel] = w.findAll('.fixed select')
    await srcSel.setValue('retention-trend')
    await typeSel.setValue('table')
    await w.find('.fixed input').setValue('留存表') // 标题（可选）
    await spanSel.setValue('12')
    await envSel.setValue('staging')
    await w.find('.fixed input[type="number"]').setValue('45') // 近 N 天
    await findBtn(w, '添加').trigger('click')
    await settle()

    expect(w.text()).not.toContain('添加 Widget')
    expect(callsOf(api, '/retention-metrics')[0]).toEqual([
      '/api/retention-metrics/g1/trend', { params: { environment: 'staging', days: 45, granularity: 'day' } }
    ])
    const added = cardFor(w, '留存表')
    expect(added).toBeTruthy()
    expect(added.classes()).toContain('md:col-span-12')
    expect(findBtn(w, '保存布局')).toBeTruthy() // dirty

    // 再加一个 online：minutes 字段分支 + 数字转换
    await findBtn(w, '+ Widget').trigger('click')
    await w.findAll('.fixed select')[0].setValue('online-overview')
    await w.find('.fixed input[type="number"]').setValue('5') // 近 N 分钟
    await findBtn(w, '添加').trigger('click')
    await settle()
    expect(callsOf(api, '/online-metrics')[0]).toEqual([
      '/api/online-metrics/g1', { params: { environment: 'staging', minutes: 5 } }
    ])
  })

  it('CRUD 兜底：后端 message 缺失回落默认文案；删空后 save 无面板早退', async () => {
    vi.stubGlobal('prompt', vi.fn(() => '新面板'))
    vi.stubGlobal('confirm', vi.fn(() => true))
    const { api, DashboardsView } = await fresh({
      dashboards: [{ id: 'd1', name: '主面板', layout: JSON.stringify({ widgets: [
        { id: 'w1', type: 'kpi', source: 'online-overview', title: '在线', span: 3, params: { minutes: 10 } }
      ] }) }]
    })
    api.post.mockRejectedValue(new Error('network'))
    api.delete.mockRejectedValue(new Error('network'))
    api.put.mockRejectedValue(new Error('network'))
    const w = await mountView(DashboardsView)

    await findBtn(w, '新建').trigger('click')
    await settle()
    expect(w.text()).toContain('创建失败')
    await w.find('.text-red-500').trigger('click') // 错误横幅关闭
    expect(w.text()).not.toContain('创建失败')

    await findBtn(w, '删除').trigger('click')
    await settle()
    expect(w.text()).toContain('删除失败')

    await w.find('button[title="移除"]').trigger('click') // 制造 dirty
    await findBtn(w, '保存布局').trigger('click')
    await settle()
    expect(w.text()).toContain('保存失败')

    api.delete.mockResolvedValue({ data: {} })
    await findBtn(w, '删除').trigger('click')
    await settle()
    expect(w.text()).toContain('还没有自定义仪表盘') // 删空 → activeId null

    w.vm.$.setupState.save() // 无活动仪表盘：早退不发 PUT
    await settle()
    expect(api.put).toHaveBeenCalledTimes(1) // 早退未追加调用
  })

  it('layout null 与缺 widgets 键：parseLayout 兜底；reload 不打断已选中仪表盘', async () => {
    const DX = { id: 'dx', name: '空布局', layout: null }
    const ctx = await fresh({
      dashboards: [{ id: 'd1', name: '主面板', layout: JSON.stringify({ widgets: [] }) }, DX]
    })
    const { api, DashboardsView } = ctx
    const w = await mountDash(ctx, DX)

    expect(w.text()).toContain('空仪表盘') // layout null → '{}' → 缺 widgets 键 → []

    await w.vm.$.setupState.load() // 再次拉取：find 命中 dx，不重置选中
    await settle()
    expect(callsOf(api, '/dashboards')).toHaveLength(2)
    expect(w.find('select').element.value).toBe('dx')
    expect(w.text()).toContain('空仪表盘')
  })

  it('addForm 注入缝：非白名单源不拼任何 params（includes false 臂）', async () => {
    const { api, DashboardsView } = await fresh({
      dashboards: [{ id: 'd1', name: '主面板', layout: JSON.stringify({ widgets: [] }) }]
    })
    const w = await mountView(DashboardsView)

    await findBtn(w, '+ Widget').trigger('click')
    // 弹层数据源下拉只有白名单 4 项，includes false 臂经 raw ref 注入缝直击
    w.vm.$.devtoolsRawSetupState.addForm.value.source = 'weird-source'
    await findBtn(w, '添加').trigger('click')
    await settle()

    expect(w.text()).toContain('数据加载失败') // fetchSource default 抛错 → 兜底文案
    for (const frag of ['/online-metrics', '/retention-metrics', '/payment-metrics', '/crash-metrics']) {
      expect(callsOf(api, frag)).toHaveLength(0)
    }
  })

  it('同 source+params 去重：进行中的请求不重复发（dataLoading 竞态臂）', async () => {
    const DASH = { id: 'd1', name: '主面板', layout: JSON.stringify({ widgets: [W_ONLINE_KPI] }) }
    const ctx = await fresh({
      dashboards: [BOOT, DASH],
      slowSources: ['online']
    })
    const { api, pending } = ctx
    const w = await mountDash(ctx, DASH)

    expect(w.text()).toContain('加载中...') // 首拉进行中
    expect(callsOf(api, '/online-metrics')).toHaveLength(1)

    // 新增同源同参 widget → loadAllWidgetData 对进行中的 key 直接跳过
    await findBtn(w, '+ Widget').trigger('click')
    await findBtn(w, '添加').trigger('click')
    await settle()
    expect(callsOf(api, '/online-metrics')).toHaveLength(1)

    pending.online.resolve()
    await settle()
    expect(w.text()).toContain('42')
    expect(callsOf(api, '/online-metrics')).toHaveLength(1) // 全程只发一次
  })
})
