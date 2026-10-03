import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { settle, waitFor } from '../helpers/settle.js'

/**
 * views/SegmentsView.vue：分群列表 + 新建弹层（条件编译校验）+ 计算/启停/删除 + 成员预览。
 *
 * - 列表：未选游戏早退（games 自动选中触发 watch）、全字段渲染（display_name/name 回落、
 *   口径中文与未知回落、conditionSummary 解析与坏 JSON '—'、member_count ?? 0、
 *   fmtTime 'T' 替换截断与 '未计算'、状态徽标 ACTIVE/INACTIVE/未知）
 * - 行操作：计算（busy 中态、memberCount 0 兜底）、启停（双向 PUT body）、删除（confirm 门控）
 * - 新建：canSubmit 门控（name trim、attribute 值、event 字段）、in 多值拆分（中英逗号）、
 *   event 归一（count Number||1、within_days 条件携带）、withinDays 0 回落 90、
 *   POST body 全字段、成功后重载与表单重置、失败 message/兜底
 * - 预览：members 列表 / 缺键空态 / 失败、subject '主体' 回落、关闭按钮与遮罩
 *
 * 组件依赖 useGameList 模块单例——每用例 resetModules + 先设 localStorage 再动态
 * import 视图；GameSelector 打桩隔离。视图内 await 均为独立语句（无内联
 * `x[k] = await` 赋值），无本环境赋值目标提前捕获问题，常规 mount 即可。
 *
 * 不可达臂：无——normalizeCondition 的 `c.op || 'gte'` / `Number(c.count) || 1` 两条 false 侧
 * （UI 门控下选不出空值）经 devtoolsRawSetupState 注入缝 + 不 yield 直击 dispatch 覆盖
 * （见「新建成功」用例，期望值同时钉住注入生效）。
 *
 * 时序说明（与 WebhooksView.spec.js 同口径）：宏任务链尾部的横幅/重载断言经条件轮询
 * waitFor（命中即返），同步路径保持直接断言。
 */
vi.mock('@/services/api', () => ({ default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() } }))

const STORAGE_KEY = 'oddsmaker.selectedGameId'

const ok = (data) => new Promise((res) => setTimeout(() => res({ data }), 0))
const bad = (err) => new Promise((_, rej) => setTimeout(() => rej(err), 0))

function deferred() {
  let resolve
  const p = new Promise((res) => { resolve = res })
  return { p, resolve }
}

const SEG_ACTIVE = {
  id: 's1', name: 'whales', display_name: '鲸鱼用户', environment: 'prod', subject: 'PLAYER',
  status: 'ACTIVE', member_count: 1200, last_computed_at: '2026-09-01T08:30:00',
  definition: JSON.stringify({ match: 'all', within_days: 90, conditions: [{ kind: 'attribute', field: 'platform', op: 'eq', value: 'ios' }] })
}
const SEG_INACTIVE = {
  id: 's2', name: 'churn_risk', display_name: null, environment: 'dev', subject: 'DEVICE',
  status: 'INACTIVE', member_count: null, last_computed_at: null,
  definition: JSON.stringify({ match: 'any', conditions: [{ kind: 'event', event_name: 'purchase', op: 'lte', count: 1 }] })
}

async function fresh({
  selectedGame = 'g1',
  games = [{ id: 'g1' }],
  segments = [SEG_ACTIVE, SEG_INACTIVE],
  segError = null,
  members = ['p1', 'p2'],
  memberError = null,
  slowSegments = false
} = {}) {
  vi.resetModules()
  localStorage.clear()
  if (selectedGame) localStorage.setItem(STORAGE_KEY, selectedGame)

  const api = (await import('@/services/api')).default
  const pending = {}
  api.get.mockImplementation((url) => {
    if (url === '/api/games') return ok({ content: games })
    if (url.endsWith('/segments')) {
      if (segError) return bad(segError)
      if (slowSegments) {
        pending.segments = deferred()
        return pending.segments.p.then(() => ({ data: segments }))
      }
      return ok(segments)
    }
    if (url.includes('/members')) return memberError ? bad(memberError) : ok({ members })
    return ok({})
  })

  const SegmentsView = (await import('@/views/SegmentsView.vue')).default
  return { api, SegmentsView, pending }
}

// settle / waitFor 统一走共享 helper（同时排空微任务 + setTimeout 宏任务队列）；
// 口径与实现见 tests/helpers/settle.js。

async function mountView(SegmentsView) {
  const w = mount(SegmentsView, { global: { stubs: { GameSelector: true } } })
  await settle()
  // 初载完成才返回（同 WebhooksView.spec.js 口径）：loading 复位在 finally 之后
  await waitFor(() => expect(findBtn(w, '刷新').attributes('disabled')).toBeUndefined())
  return w
}

const findBtn = (w, label) => w.findAll('button').find((b) => b.text() === label)
const rowFor = (w, frag) => w.findAll('tbody tr').find((r) => r.text().includes(frag))
const callsOf = (api, frag) => api.get.mock.calls.filter((c) => c[0].includes(frag))

beforeEach(() => {
  vi.clearAllMocks()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('SegmentsView', () => {
  it('未选游戏：load 早退不发分群请求；games 自动选中触发 watch 拉取一次', async () => {
    const { api, SegmentsView } = await fresh({ selectedGame: null })
    const w = await mountView(SegmentsView)

    await waitFor(() => {
      expect(callsOf(api, '/segments')).toHaveLength(1) // 早退那轮不发，自动选中后 watch 触发
      expect(w.text()).toContain('鲸鱼用户')
    })
  })

  it('列表渲染：名称回落、口径中文/未知回落、条件摘要、成员数与时间兜底、状态徽标', async () => {
    const w = await mountView((await fresh()).SegmentsView)

    const r1 = rowFor(w, 'whales')
    expect(r1.text()).toContain('鲸鱼用户')
    expect(r1.text()).toContain('whales')
    expect(r1.text()).toContain('prod')
    expect(r1.text()).toContain('玩家')
    expect(r1.text()).toContain('全部满足 · 1 个条件')
    expect(r1.text()).toContain('1,200')
    expect(r1.text()).toContain('2026-09-01 08:30:00') // 'T' 替换 + 截断 19 位
    expect(r1.text()).toContain('启用')

    const r2 = rowFor(w, 'churn_risk')
    expect(r2.text()).toContain('churn_risk') // display_name null 回落 name
    expect(r2.text()).toContain('设备')
    expect(r2.text()).toContain('任一满足 · 1 个条件')
    expect(r2.text()).toContain('0') // member_count ?? 0
    expect(r2.text()).toContain('未计算')
    expect(r2.text()).toContain('停用')
  })

  it('conditionSummary 坏 JSON 与未知 subject/状态回落', async () => {
    const { SegmentsView } = await fresh({
      segments: [
        { ...SEG_ACTIVE, definition: '{not-json', subject: 'ACCOUNT', status: 'PENDING' },
        { ...SEG_ACTIVE, id: 's4', name: 'bare', definition: '{}' }, // 无 conditions 键 → '|| []' 右臂
        { ...SEG_ACTIVE, id: 's5', name: 'nulldef', definition: null } // definition null → "|| '{}'" 右臂
      ]
    })
    const w = await mountView(SegmentsView)
    const r = rowFor(w, 'whales')
    expect(r.text()).toContain('—') // 坏 JSON → catch 回落
    expect(r.text()).toContain('ACCOUNT') // 未知口径原样透出
    expect(r.text()).toContain('停用') // 非 ACTIVE → 停用
    expect(r.find('span.rounded-full').classes().join(' ')).toContain('bg-gray-100')
    expect(rowFor(w, 'bare').text()).toContain('0 个条件')
    expect(rowFor(w, 'nulldef').text()).toContain('0 个条件')
  })

  it('列表失败臂：后端 message 与无 response 兜底，横幅可关闭', async () => {
    const errSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const { api, SegmentsView } = await fresh({ segError: { response: { data: { message: '无权限' } } } })
    const w = await mountView(SegmentsView)
    expect(w.text()).toContain('无权限')
    await w.find('.text-red-500').trigger('click')
    expect(w.text()).not.toContain('无权限')

    const { api: api2, SegmentsView: V2 } = await fresh({ segError: new Error('x') })
    const w2 = await mountView(V2)
    expect(w2.text()).toContain('加载分群列表失败')
    expect(errSpy).toHaveBeenCalled()
    expect(api2).toBeDefined()
  })

  it('空列表与加载中：空态引导；慢速请求先出 spinner 再着陆', async () => {
    const { api, SegmentsView, pending } = await fresh({ segments: [], slowSegments: true })
    const w = mount(SegmentsView, { global: { stubs: { GameSelector: true } } })
    await flushPromises()
    expect(w.text()).not.toContain('还没有分群') // 仍在 loading
    expect(w.text()).toContain('加载中')
    pending.segments.resolve()
    await settle()
    await waitFor(() => {
      expect(w.text()).toContain('还没有分群')
      expect(w.text()).toContain('创建一个分群')
      expect(callsOf(api, '/segments')).toHaveLength(1)
    })

    await findBtn(w, '刷新').trigger('click')
    await settle()
    await waitFor(() => expect(callsOf(api, '/segments')).toHaveLength(2))
  })

  it('计算成功：busy 中态、member_count 就地更新、成功文案含千分位', async () => {
    const { api, SegmentsView } = await fresh({ segments: [SEG_ACTIVE] })
    const pend = deferred() // 计算请求挂起：busy 中态可断言（fresh 的 pending 只服务慢分群列表）
    api.post.mockImplementation((url) =>
      url.includes('/compute') ? pend.p.then(() => ({ data: { memberCount: 1234 } })) : ok({}))
    const w = await mountView(SegmentsView)

    await findBtn(w, '计算').trigger('click')
    expect(rowFor(w, 'whales').text()).toContain('计算中…') // busyId 中态
    expect(api.post).toHaveBeenCalledWith('/api/segments/s1/compute')

    pend.resolve()
    await settle()
    await waitFor(() => {
      expect(rowFor(w, 'whales').text()).toContain('1,234')
      expect(w.text()).toContain('「鲸鱼用户」计算完成：1,234 名成员')
      expect(rowFor(w, 'whales').text()).not.toContain('未计算') // last_computed_at 已刷新
    })

    // memberCount 0 → '0 名成员' 兜底
    api.post.mockImplementation((url) =>
      url.includes('/compute') ? ok({ memberCount: 0 }) : ok({}))
    await findBtn(w, '计算').trigger('click')
    await settle()
    await waitFor(() => expect(w.text()).toContain('计算完成：0 名成员'))
  })

  it('计算失败臂：后端 message 与无 response 兜底', async () => {
    const { api, SegmentsView } = await fresh({ segments: [SEG_ACTIVE] })
    api.post.mockRejectedValueOnce({ response: { data: { message: 'ck 挂了' } } })
    const w = await mountView(SegmentsView)

    await findBtn(w, '计算').trigger('click')
    await settle()
    expect(w.text()).toContain('ck 挂了')
    expect(rowFor(w, 'whales').text()).toContain('计算') // busy 复位

    api.post.mockRejectedValueOnce(new Error('x'))
    await findBtn(w, '计算').trigger('click')
    await settle()
    expect(w.text()).toContain('计算失败')
  })

  it('启停：双向 PUT body 与状态徽标就地翻转；失败臂兜底', async () => {
    const { api, SegmentsView } = await fresh()
    api.put.mockResolvedValueOnce({ data: { ...SEG_ACTIVE, status: 'INACTIVE' } })
    api.put.mockResolvedValueOnce({ data: { ...SEG_INACTIVE, status: 'ACTIVE' } })
    const w = await mountView(SegmentsView)

    await rowFor(w, 'whales').findAll('button').find((b) => b.text() === '停用').trigger('click')
    await settle()
    expect(api.put).toHaveBeenCalledWith('/api/segments/s1', { status: 'INACTIVE' })
    expect(rowFor(w, 'whales').text()).toContain('停用')

    await rowFor(w, 'churn_risk').findAll('button').find((b) => b.text() === '启用').trigger('click')
    await settle()
    expect(api.put).toHaveBeenCalledWith('/api/segments/s2', { status: 'ACTIVE' })
    expect(rowFor(w, 'churn_risk').text()).toContain('启用')

    api.put.mockRejectedValueOnce(new Error('x'))
    // whales 已切成 INACTIVE（按钮变「启用」），失败臂改用已切成 ACTIVE 的 churn_risk 行
    await rowFor(w, 'churn_risk').findAll('button').find((b) => b.text() === '停用').trigger('click')
    await settle()
    expect(w.text()).toContain('状态更新失败')
  })

  it('删除：confirm 门控；确认后行移除与成功文案（display_name 回落）；失败兜底', async () => {
    vi.stubGlobal('confirm', vi.fn(() => false))
    const { api, SegmentsView } = await fresh()
    const w = await mountView(SegmentsView)

    await rowFor(w, 'whales').findAll('button').find((b) => b.text() === '删除').trigger('click')
    expect(api.delete).not.toHaveBeenCalled() // confirm 取消

    vi.stubGlobal('confirm', vi.fn(() => true))
    api.delete.mockResolvedValueOnce({ data: {} })
    await rowFor(w, 'whales').findAll('button').find((b) => b.text() === '删除').trigger('click')
    await settle()
    expect(api.delete).toHaveBeenCalledWith('/api/segments/s1')
    expect(rowFor(w, 'whales')).toBeUndefined()
    expect(w.text()).toContain('分群「鲸鱼用户」已删除')

    api.delete.mockRejectedValueOnce(new Error('x'))
    await rowFor(w, 'churn_risk').findAll('button').find((b) => b.text() === '删除').trigger('click')
    await settle()
    expect(w.text()).toContain('删除失败')

    api.delete.mockRejectedValueOnce({ response: { data: { message: '无权限删除' } } })
    await rowFor(w, 'churn_risk').findAll('button').find((b) => b.text() === '删除').trigger('click')
    await settle()
    expect(w.text()).toContain('无权限删除') // 后端 message 优先臂

    api.delete.mockRejectedValueOnce({ response: {} }) // 半残 response：?.data 短路 → 兜底臂
    await rowFor(w, 'churn_risk').findAll('button').find((b) => b.text() === '删除').trigger('click')
    await settle()
    expect(w.text()).toContain('删除失败')

    api.delete.mockRejectedValueOnce({ response: { data: {} } }) // data 存在但 message 缺失 → ?.message 短路臂
    await rowFor(w, 'churn_risk').findAll('button').find((b) => b.text() === '删除').trigger('click')
    await settle()
    expect(w.text()).toContain('删除失败')

    // 无 display_name 的分群删除成功 → 文案回落 name（右臂）
    api.delete.mockResolvedValueOnce({ data: {} })
    await rowFor(w, 'churn_risk').findAll('button').find((b) => b.text() === '删除').trigger('click')
    await settle()
    expect(api.delete).toHaveBeenCalledWith('/api/segments/s2')
    expect(rowFor(w, 'churn_risk')).toBeUndefined()
    expect(w.text()).toContain('分群「churn_risk」已删除')
  })

  it('成员预览：列表渲染、缺键空态、失败兜底、subject 主体回落、关闭按钮与遮罩', async () => {
    const { api, SegmentsView } = await fresh({
      segments: [SEG_ACTIVE, { ...SEG_ACTIVE, id: 's3', name: 'naked', display_name: null, subject: undefined }],
      members: ['p1', 'p2', 'p3']
    })
    const w = await mountView(SegmentsView)

    await rowFor(w, 'whales').findAll('button').find((b) => b.text() === '成员').trigger('click')
    expect(w.text()).toContain('加载中') // 预览 loading 中态
    await settle()
    expect(api.get).toHaveBeenCalledWith('/api/segments/s1/members', { params: { limit: 100 } })
    await waitFor(() => {
      expect(w.text()).toContain('成员预览')
      expect(w.text()).toContain('玩家 ID') // subjectLabels 命中
      expect(w.findAll('.fixed li').map((li) => li.text())).toEqual(['p1', 'p2', 'p3'])
    })

    // 关闭按钮
    await findBtn(w, '关闭').trigger('click')
    expect(w.text()).not.toContain('成员预览')

    // 缺 members 键 → 空态
    api.get.mockImplementation((url) =>
      url.includes('/members') ? ok({}) : url === '/api/games' ? ok({ content: [{ id: 'g1' }] }) : ok([]))
    await rowFor(w, 'whales').findAll('button').find((b) => b.text() === '成员').trigger('click')
    await settle()
    await waitFor(() => expect(w.text()).toContain('暂无成员'))

    // 失败臂 + subject 缺失 → '主体'（用无 subject 的 naked 行开预览）
    api.get.mockImplementation((url) =>
      url.includes('/members') ? bad(new Error('x')) : url === '/api/games' ? ok({ content: [{ id: 'g1' }] }) : ok([]))
    await rowFor(w, 'naked').findAll('button').find((b) => b.text() === '成员').trigger('click')
    await settle()
    await waitFor(() => {
      expect(w.text()).toContain('加载成员失败')
      expect(w.text()).toContain('主体 ID')
      expect(w.text()).toContain('「naked」') // 预览头 display_name 缺省回落 name
    })

    // 遮罩关闭
    await w.find('.fixed .absolute').trigger('click')
    expect(w.text()).not.toContain('成员预览')
  })

  it('新建弹层校验：canSubmit 门控（name trim、attribute 值、event 字段）；条件增删', async () => {
    const { SegmentsView } = await fresh({ segments: [] })
    const w = await mountView(SegmentsView)

    await findBtn(w, '新建分群').trigger('click')
    expect(w.text()).toContain('标识名') // 弹层开启（页头按钮与弹层 h2 同名，用弹层内 label 断言）
    const createBtn = () => w.findAll('button').find((b) => b.text() === '创建')
    expect(createBtn().attributes('disabled')).toBeDefined() // name 空

    await w.find('input[placeholder="whales"]').setValue('   ')
    expect(createBtn().attributes('disabled')).toBeDefined() // 纯空白 trim 后仍空

    await w.find('input[placeholder="whales"]').setValue('new_seg')
    expect(createBtn().attributes('disabled')).toBeDefined() // attribute 值空

    await w.find('input[placeholder="ios"]').setValue('ios')
    expect(createBtn().attributes('disabled')).toBeUndefined()

    // 切 event：eventName/count 字段门控（弹层 select 序 env0/subject1/match2/块内 kind3/field4/op5）
    const sels = () => w.findAll('.fixed select')
    await sels()[3].setValue('event')
    // 注入缝：eventName 为 nullish → `?? ''` 右侧臂（null/undefined 均非空串字面量路径）
    w.vm.$.devtoolsRawSetupState.form.value.conditions[0].eventName = null
    await flushPromises()
    expect(createBtn().attributes('disabled')).toBeDefined()
    await w.find('input[placeholder="事件名，如 purchase"]').setValue('purchase')
    expect(createBtn().attributes('disabled')).toBeUndefined() // count 默认 1
    await w.findAll('.fixed input[type="number"]')[1].setValue('0') // 块内 count（0=回看窗口）
    expect(createBtn().attributes('disabled')).toBeDefined() // count 0
    await w.findAll('.fixed input[type="number"]')[1].setValue('2')

    // 切 event_absent：仅 eventName + 窗口
    await sels()[3].setValue('event_absent')
    expect(createBtn().attributes('disabled')).toBeUndefined()
    await w.findAll('.fixed input[type="number"]')[1].setValue('7') // event_absent 窗口输入（无 count）
    await w.find('input[placeholder="事件名，如 purchase"]').setValue('')
    expect(createBtn().attributes('disabled')).toBeDefined()

    // 回到 attribute：field 下拉 + in 操作符占位切换
    await sels()[3].setValue('attribute')
    await sels()[4].setValue('country') // field v-model 处理器
    await sels()[5].setValue('in')
    expect(w.find('input[placeholder="ios,android（逗号分隔）"]').exists()).toBe(true)

    // 条件增删：初始 1 个时移除禁用，添加后可移除
    const removeBtns = () => w.findAll('.fixed button').filter((b) => b.text() === '移除')
    expect(removeBtns()[0].attributes('disabled')).toBeDefined()
    await findBtn(w, '+ 添加条件').trigger('click')
    expect(removeBtns()).toHaveLength(2)
    await removeBtns()[1].trigger('click')
    expect(removeBtns()).toHaveLength(1)

    // 注入缝：attribute 值为数组时 Array.isArray 臂放行（[] 使 String() 为空，走 Array.isArray 右臂）
    w.vm.$.devtoolsRawSetupState.form.value.conditions[0].value = []
    await flushPromises()
    expect(createBtn().attributes('disabled')).toBeUndefined()
    // 注入缝：value nullish → `?? ''` 右臂（null 两侧皆空 → 门控回到禁用，结果可区分）
    w.vm.$.devtoolsRawSetupState.form.value.conditions[0].value = null
    await flushPromises()
    expect(createBtn().attributes('disabled')).toBeDefined()

    // 遮罩关闭
    await w.find('.fixed .absolute').trigger('click')
    expect(w.text()).not.toContain('标识名')
  })

  it('新建成功：POST body 全字段（in 中英逗号拆分、event 归一、withinDays 0 回落 90）；弹层关闭、重载、表单重置', async () => {
    const { api, SegmentsView } = await fresh({ segments: [SEG_ACTIVE] })
    api.post.mockImplementation((url) => (url.includes('/compute') ? ok({ memberCount: 1 }) : ok({})))
    const w = await mountView(SegmentsView)

    await findBtn(w, '新建分群').trigger('click')
    await w.find('input[placeholder="whales"]').setValue('new_seg')
    await w.find('input[placeholder="鲸鱼用户"]').setValue('鲸 2.0')
    const sels = () => w.findAll('.fixed select') // 序：env0/subject1/match2/块内 kind3/field4/op5
    await sels()[0].setValue('staging') // 环境
    await sels()[1].setValue('DEVICE') // 口径
    await sels()[2].setValue('any') // 条件组合
    await w.find('.fixed input[type="number"]').setValue('0') // 回看窗口 → within_days 90
    await sels()[5].setValue('in') // 条件1 op
    await w.find('input[placeholder="ios,android（逗号分隔）"]').setValue('ios, android，平板')

    await findBtn(w, '+ 添加条件').trigger('click')
    const blocks = () => w.findAll('.fixed .rounded-md.border')
    await blocks()[1].find('select').setValue('event') // 块2 kind
    await blocks()[1].find('input[placeholder="事件名，如 purchase"]').setValue('purchase')
    await blocks()[1].findAll('select')[1].setValue('lte') // event 分支 op 变更处理器（模板 356 行）
    await blocks()[1].findAll('input[type="number"]')[0].setValue('2') // count
    await blocks()[1].findAll('input[type="number"]')[1].setValue('14') // 窗口

    await findBtn(w, '+ 添加条件').trigger('click')
    await blocks()[2].find('select').setValue('event') // 块3 kind：不设窗口 → within_days 缺省臂
    await blocks()[2].find('input[placeholder="事件名，如 purchase"]').setValue('login')

    // 注入缝：块2 op 置空 → `c.op || 'gte'` false 侧；块3 count 置空 → `Number(c.count) || 1`
    // false 侧（validCondition 不校验 op；canSubmit 此时会禁用按钮且 jsdom 对 disabled 不派发
    // click——注入与 dispatch 之间不 yield，DOM 尚未重渲染，click 正常触发；create 自身不复检
    // canSubmit）。期望 op 'gte' / count 1 同时钉住注入确实生效
    w.vm.$.devtoolsRawSetupState.form.value.conditions[1].op = ''
    w.vm.$.devtoolsRawSetupState.form.value.conditions[2].count = ''
    const btn = w.findAll('button').find((b) => b.text() === '创建')
    await btn.trigger('click')
    await settle()

    expect(api.post).toHaveBeenCalledTimes(1)
    const [url, body] = api.post.mock.calls[0]
    expect(url).toBe('/api/games/g1/segments')
    expect(body.name).toBe('new_seg')
    expect(body.display_name).toBe('鲸 2.0')
    expect(body.environment).toBe('staging')
    expect(body.subject).toBe('DEVICE')
    const def = JSON.parse(body.definition)
    expect(def.match).toBe('any')
    expect(def.within_days).toBe(90) // Number(0) || 90
    expect(def.conditions).toEqual([
      { kind: 'attribute', field: 'platform', op: 'in', value: ['ios', 'android', '平板'] },
      { kind: 'event', event_name: 'purchase', op: 'gte', count: 2, within_days: 14 }, // op 空 → 'gte' 回落（false 侧）
      { kind: 'event', event_name: 'login', op: 'eq', count: 1 } // count '' → 1 回落（false 侧）、op 'eq' 原样、无窗口
    ])

    expect(w.text()).toContain('分群「new_seg」已创建')
    await waitFor(() => {
      expect(w.text()).not.toContain('标识名') // 弹层关闭（页头按钮同名残留，改用弹层内 label）
      expect(callsOf(api, '/segments')).toHaveLength(2) // 成功后 load() 重载
    })

    // 表单重置：重开弹层 name 为空、条件回到 1 个默认项
    await findBtn(w, '新建分群').trigger('click')
    expect(w.find('input[placeholder="whales"]').element.value).toBe('')
    expect(w.findAll('.fixed .rounded-md.border')).toHaveLength(1)
  })

  it('新建失败臂：后端 message 与无 response 兜底在弹层内展示', async () => {
    const { api, SegmentsView } = await fresh({ segments: [] })
    const w = await mountView(SegmentsView)

    await findBtn(w, '新建分群').trigger('click')
    api.post.mockRejectedValueOnce({ response: { data: { message: '名称已存在' } } })
    await w.find('input[placeholder="whales"]').setValue('dup')
    await w.find('input[placeholder="ios"]').setValue('ios')
    await findBtn(w, '创建').trigger('click')
    await settle()
    expect(w.text()).toContain('名称已存在')
    expect(w.text()).toContain('标识名') // 弹层保持

    api.post.mockRejectedValueOnce(new Error('x'))
    await findBtn(w, '创建').trigger('click')
    await settle()
    expect(w.text()).toContain('创建失败')

    await findBtn(w, '取消').trigger('click')
    expect(w.text()).not.toContain('标识名')
  })

  it('成功横幅可关闭；display_name 缺省时操作文案回落 name', async () => {
    const { api, SegmentsView } = await fresh({ segments: [SEG_INACTIVE] }) // 无 display_name
    api.post.mockImplementation((url) => (url.includes('/compute') ? ok({ memberCount: 5 }) : ok({})))
    const w = await mountView(SegmentsView)

    await findBtn(w, '计算').trigger('click')
    await settle()
    await waitFor(() => expect(w.text()).toContain('「churn_risk」计算完成：5 名成员'))
    await w.find('.text-green-500').trigger('click')
    expect(w.text()).not.toContain('计算完成')
  })
})
