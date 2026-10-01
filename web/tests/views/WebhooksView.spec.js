import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

/**
 * views/WebhooksView.vue：Webhook 配置列表 + 统计卡 + 新建/编辑弹层（必填与 authConfig
 * JSON 校验）+ 连通性测试（busy/结果卡）+ 发送日志弹层 + 启停/删除。
 *
 * - 列表：displayName||name 回落、description v-if、statusColors（ACTIVE/INACTIVE/PAUSED）、
 *   eventTypes null→「全部事件」/逗号 chips、environmentId||'全部'、成功/失败计数、
 *   fmtTime 'T' 替换截断与 '-'、停用/启用按钮与着色、stats 四卡 v-if、空态 loading 双臂
 * - load：未选游戏早退（games 自动选中触发 watch 拉一次）、Promise.all 双请求、
 *   刷新清 testResult、失败 4 变体（后端 message / ?.data / ?. / 兜底）+ console.error
 * - 新建：必填两臂、authConfig JSON 校验（null/数组/空对象/非对象四子臂 + 新建缺填臂）、
 *   POST body 全字段（trim、''→null、authConfig 按需携带、Number 归一）+ 成功重置
 * - 编辑：openEdit `||`/`??` 双侧回填（稀疏脏数据 name/url 空、authType null、
 *   超时三键 null）、secret 不回显留空=保留（PUT 无 authConfig 键）、更新文案
 * - 启停/删除：PUT body {...config,status} 双向、confirm 门控与文案双侧（参数直断）、
 *   失败 4 变体
 * - 测试/日志：busy 中态、结果卡 success/failed × logId 双侧、日志表 ??/!= null/||
 *   全双侧、loading/列表/空态三臂、@click.self 与关闭按钮
 *
 * 不可达臂（记账不硬凑）：无——全部分支实测触达（含各 catch 的 e.response?.data?.message
 * 链四变体：message / response:{} / response:{data:{}} / 无 response）。
 *
 * 时序说明：ok/bad 走 setTimeout 宏任务；submitForm/toggleStatus/removeConfig 内均
 * `await load()`（Promise.all 双请求），盒子高负载（本机有 Tauri 链接 + 安卓模拟器）时
 * 固定轮数 settle 边际不足致用例漂移红（与 sdks/web flaky 同根因）。宏任务链尾部的横幅/
 * 重载断言一律经条件轮询 waitFor（命中即返，绿路径不加耗时；30 轮未命中抛原断言错），
 * 同步路径（回填/占位/busy 中态/confirm 参数直断）保持直接断言不掩盖真问题。
 * 另两处跟源码实测修正：成功重载后 GET 计两次（首载+重载）；编辑/新建成功文案取表单
 * name（submitForm 用 f.name.trim() 而非 displayName）。
 */
vi.mock('@/services/api', () => ({ default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() } }))

const STORAGE_KEY = 'oddsmaker.selectedGameId'

const ok = (data) => new Promise((res) => setTimeout(() => res({ data }), 0))
const bad = (err) => new Promise((_, rej) => setTimeout(() => rej(err), 0))

function deferred() {
  let resolve
  let reject
  const p = new Promise((res, rej) => { resolve = res; reject = rej })
  return { p, resolve, reject }
}

const CFG_ACTIVE = {
  id: 'w1', name: 'slack-alerts', displayName: 'Slack 告警',
  webhookUrl: 'https://hooks.example.com/x', environmentId: 'prod',
  description: '风控告警推送', eventTypes: 'risk_case,block', riskLevels: 'HIGH',
  authType: 'none', timeoutSeconds: 30, maxRetries: 3, retryBackoffMs: 1000,
  status: 'ACTIVE', totalSuccess: 120, totalFailed: 2, lastSentAt: '2026-09-30T10:20:30'
}
const CFG_BARE = {
  id: 'w2', name: 'audit-sink', displayName: null,
  webhookUrl: 'https://audit.example.com', environmentId: null,
  description: null, eventTypes: null, riskLevels: null,
  authType: 'basic', timeoutSeconds: null, maxRetries: null, retryBackoffMs: null,
  status: 'INACTIVE', totalSuccess: 0, totalFailed: 0, lastSentAt: null
}
// 稀疏脏数据：name/url 空串、authType null —— openEdit 各 `||` false 侧直达
const CFG_SPARSE = {
  id: 'w3', name: '', displayName: '稀疏配置', webhookUrl: '',
  environmentId: null, description: null, eventTypes: null, riskLevels: null,
  authType: null, timeoutSeconds: null, maxRetries: null, retryBackoffMs: null,
  status: 'PAUSED', totalSuccess: 7, totalFailed: 1, lastSentAt: null
}
const STATS = { totalConfigs: 3, activeConfigs: 1, totalSent: 127, totalFailed: 3 }
const LOGS = [
  { id: 1, sentAt: '2026-09-30T08:00:00', eventType: 'risk_case', deliveryStatus: 'SUCCESS', responseStatus: 200, responseTimeMs: 95, retryCount: 0, errorMessage: '' },
  { id: 2, sentAt: null, eventType: 'block', deliveryStatus: 'FAILED', responseStatus: null, responseTimeMs: null, retryCount: 2, errorMessage: 'connection refused' },
  { id: 3, sentAt: '2026-09-30T09:00:00', eventType: 'metric_alert', deliveryStatus: 'RETRYING', responseStatus: 503, responseTimeMs: null, retryCount: undefined, errorMessage: null }
]

// catch 链四变体：后端 message / response:{}（?.data 短路）/ response:{data:{}}（?. 短路）/ 无 response
const CHAIN_VARIANTS = [
  [{ response: { data: { message: '后端拒绝了' } } }, '后端拒绝了'],
  [{ response: {} }, null],
  [{ response: { data: {} } }, null],
  [new Error('x'), null]
]

async function fresh({
  selectedGame = 'g1',
  games = [{ id: 'g1' }],
  configs = [CFG_ACTIVE, CFG_BARE, CFG_SPARSE],
  stats = STATS,
  logs = LOGS,
  webhooksError = null,
  logsError = null,
  slowConfigs = false,
  slowLogs = false
} = {}) {
  vi.resetModules()
  localStorage.clear()
  if (selectedGame) localStorage.setItem(STORAGE_KEY, selectedGame)

  const api = (await import('@/services/api')).default
  const pending = {}
  const route = (url) => {
    if (url === '/api/games') return ok({ content: games })
    if (url.includes('/webhooks/stats/')) return ok(stats)
    if (url.includes('/webhooks/logs/')) {
      if (logsError) return bad(logsError)
      if (slowLogs) {
        pending.logs = deferred()
        return pending.logs.p.then(() => ({ data: logs }))
      }
      return ok(logs)
    }
    if (url.includes('/webhooks/game/')) {
      if (webhooksError) return bad(webhooksError)
      if (slowConfigs) {
        pending.configs = deferred()
        return pending.configs.p.then(() => ({ data: configs }))
      }
      return ok(configs)
    }
    return ok({})
  }
  api.get.mockImplementation(route)

  const WebhooksView = (await import('@/views/WebhooksView.vue')).default
  return { api, WebhooksView, pending, route }
}

const settle = async () => {
  await flushPromises()
  await flushPromises()
  await flushPromises()
  await flushPromises()
  await flushPromises()
}

// 条件等待：load 链（PUT/POST/DELETE → await load() → 双 setTimeout 宏任务 → 渲染）在
// 高负载下固定轮数 settle 可能不够（与 sdks/web flaky 同根因），此处按条件轮询，
// 命中即返（绿路径不增加耗时），30 轮仍未命中则抛最后一次断言错误（真 bug 照常红）。
async function waitFor(assertFn, tries = 30) {
  let last
  for (let i = 0; i < tries; i++) {
    try {
      assertFn()
      return
    } catch (e) { last = e }
    await settle()
  }
  throw last
}

async function mountView(WebhooksView) {
  const w = mount(WebhooksView, { global: { stubs: { GameSelector: true } } })
  await settle()
  // 初载完成才返回：loading 复位在 load() finally（赋值之后），按条件等而非固定轮数
  //（高负载下 settle 可能 starvation，行未渲染即返回会导致后续 rowBtn 空指针）
  await waitFor(() => expect(findBtn(w, '刷新').attributes('disabled')).toBeUndefined())
  return w
}

const findBtn = (w, label) => w.findAll('button').find((b) => b.text().trim() === label)
const rowFor = (w, frag) => w.findAll('tbody tr').find((r) => r.text().includes(frag))
const rowBtn = (w, frag, label) => {
  const r = rowFor(w, frag)
  return r && r.findAll('button').find((b) => b.text().trim() === label)
}
const badge = (w, label) => w.findAll('span').find((s) => s.text().trim() === label)
const callsOf = (api, frag) => api.get.mock.calls.filter((c) => c[0].includes(frag))

beforeEach(() => {
  vi.clearAllMocks()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('WebhooksView', () => {
  it('未选游戏：load 早退不发 Webhook 请求；games 自动选中触发 watch 拉取一次', async () => {
    const { api, WebhooksView } = await fresh({ selectedGame: null })
    const w = await mountView(WebhooksView)

    await waitFor(() => {
      expect(callsOf(api, '/webhooks/game/')).toHaveLength(1) // 早退那轮不发，自动选中后 watch 触发
      expect(callsOf(api, '/webhooks/stats/')).toHaveLength(1)
      expect(w.text()).toContain('Slack 告警')
    })
  })

  it('列表+统计：三行全字段（显示名回落、状态着色、事件 chips/全部、环境兜底、时间格式、按钮态）', async () => {
    const { WebhooksView } = await fresh()
    const w = await mountView(WebhooksView)

    // 统计四卡
    expect(w.text()).toContain('配置总数')
    expect(w.text()).toContain('3')
    expect(w.text()).toContain('活跃配置')
    expect(w.text()).toContain('127')
    expect(w.text()).toContain('累计失败')
    expect(findBtn(w, '刷新').attributes('disabled')).toBeUndefined() // loading 假

    // 行1：全有值
    const r1 = rowFor(w, 'Slack 告警')
    expect(r1.text()).toContain('风控告警推送')
    expect(r1.text()).toContain('https://hooks.example.com/x')
    expect(badge(w, 'ACTIVE').classes().join(' ')).toContain('bg-green-100')
    expect(r1.text()).toContain('risk_case')
    expect(r1.text()).toContain('block')
    expect(r1.text()).toContain('prod')
    expect(r1.text()).toContain('120')
    expect(r1.text()).toContain('2026-09-30 10:20:30') // 'T' 替换 + 截断 19 位
    expect(r1.text()).toContain('停用') // ACTIVE → 停用
    expect(rowBtn(w, 'Slack 告警', '停用').classes().join(' ')).toContain('text-yellow-600')
    expect(rowBtn(w, 'Slack 告警', '测试')).toBeDefined()
    expect(rowBtn(w, 'Slack 告警', '日志')).toBeDefined()
    expect(rowBtn(w, 'Slack 告警', '编辑')).toBeDefined()
    expect(rowBtn(w, 'Slack 告警', '删除')).toBeDefined()

    // 行2：displayName null 回落 name、空订阅/空环境/空时间兜底
    const r2 = rowFor(w, 'audit-sink')
    expect(r2.text()).not.toContain('null')
    expect(r2.find('p').exists()).toBe(false) // description null → 无说明段
    expect(r2.findAll('span').find((s) => s.text() === 'INACTIVE').classes().join(' ')).toContain('bg-gray-100')
    expect(r2.text()).toContain('全部事件')
    expect(r2.text()).toContain('全部')
    expect(r2.text()).toContain('0')
    expect(r2.text()).toContain('-') // lastSentAt null
    expect(rowBtn(w, 'audit-sink', '启用')).toBeDefined()
    expect(rowBtn(w, 'audit-sink', '启用').classes().join(' ')).toContain('text-green-600')

    // 行3：PAUSED 着色 + displayName 回落
    const r3 = rowFor(w, '稀疏配置')
    expect(r3.findAll('span').find((s) => s.text() === 'PAUSED').classes().join(' ')).toContain('bg-yellow-100')
    expect(r3.text()).toContain('7')
    expect(r3.text()).toContain('1')
  })

  it('空态双臂：慢请求先出「加载中...」，落空后出暂无配置文案；刷新再拉一次', async () => {
    const { api, WebhooksView, pending } = await fresh({ configs: [], slowConfigs: true })
    const w = mount(WebhooksView, { global: { stubs: { GameSelector: true } } })
    await flushPromises()
    expect(w.text()).toContain('加载中...') // loading 臂
    expect(w.text()).not.toContain('暂无 Webhook') // 落地前不判空
    pending.configs.resolve()
    await settle()
    expect(w.text()).toContain('当前游戏暂无 Webhook 配置，点击右上角「新建配置」创建')

    await findBtn(w, '刷新').trigger('click')
    await settle()
    await waitFor(() => expect(callsOf(api, '/webhooks/game/')).toHaveLength(2))
  })

  it('load 失败 4 变体：后端 message、?.data 短路、?. 短路、无 response 兜底 + console.error', async () => {
    const errSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const { api, WebhooksView, route } = await fresh({ webhooksError: { response: { data: { message: '无权限读取' } } } })
    const w = await mountView(WebhooksView)
    expect(w.text()).toContain('无权限读取')

    let n = 1
    for (const [variant, expectMsg] of CHAIN_VARIANTS.slice(1)) {
      // 只拒 Webhook 请求，/api/games 走原路由（useGameList 依赖）
      api.get.mockImplementation((url) => (url.includes('/api/webhooks/') ? bad(variant) : route(url)))
      await findBtn(w, '刷新').trigger('click')
      await settle()
      n++
      if (expectMsg) await waitFor(() => expect(w.text()).toContain(expectMsg))
      else await waitFor(() => expect(w.text()).toContain('加载 Webhook 配置失败'))
    }
    expect(errSpy).toHaveBeenCalledTimes(n)
    errSpy.mockRestore()
  })

  it('stats 为 null：统计区不渲染；刷新按钮重载列表', async () => {
    const { api, WebhooksView } = await fresh({ stats: null })
    const w = await mountView(WebhooksView)
    expect(w.text()).not.toContain('配置总数')
    expect(w.text()).toContain('Slack 告警') // 列表照常

    await findBtn(w, '刷新').trigger('click')
    await settle()
    await waitFor(() => expect(callsOf(api, '/webhooks/game/')).toHaveLength(2))
  })

  it('新建必填两臂（name/url 各自缺）；authConfig 面板随鉴权方式显隐；遮罩 @click.self 关闭', async () => {
    const { api, WebhooksView } = await fresh({ configs: [] })
    const w = await mountView(WebhooksView)

    await findBtn(w, '新建配置').trigger('click')
    expect(w.text()).toContain('新建 Webhook 配置')
    expect(w.find('textarea.font-mono').exists()).toBe(false) // authType none → 无鉴权面板

    await findBtn(w, '保存').trigger('click') // name/url 双空 → || 左真
    expect(w.text()).toContain('名称与 URL 为必填项')
    await w.find('input[placeholder="如 slack-alerts"]').setValue('hook-x')
    await findBtn(w, '保存').trigger('click') // name 有值 → || 右真
    expect(w.text()).toContain('名称与 URL 为必填项')
    expect(api.post).not.toHaveBeenCalled()
    expect(w.text()).toContain('新建 Webhook 配置') // 弹层保持

    await w.find('.fixed select').setValue('basic')
    expect(w.find('textarea.font-mono').exists()).toBe(true) // 非 none → 面板出现

    await w.find('.fixed').trigger('click') // @click.self → 关闭
    expect(w.text()).not.toContain('新建 Webhook 配置')

    await findBtn(w, '新建配置').trigger('click') // 重开 → 右上「关闭」按钮（模板 374 行）
    await w.find('.fixed button.text-gray-400').trigger('click')
    expect(w.text()).not.toContain('新建 Webhook 配置')
  })

  it('新建 authConfig 校验五臂：缺填、非 JSON、null、数组、空对象、非对象', async () => {
    const { api, WebhooksView } = await fresh({ configs: [] })
    const w = await mountView(WebhooksView)

    await findBtn(w, '新建配置').trigger('click')
    await w.find('input[placeholder="如 slack-alerts"]').setValue('hook-x')
    await w.find('input[placeholder="https://..."]').setValue('https://h')
    await w.find('.fixed select').setValue('basic')
    expect(w.find('textarea.font-mono').attributes('placeholder')).toContain('如 {') // create 模式占位臂
    expect(w.text()).toContain('*') // 新建时 authConfig 必填星标（!editing 臂）

    await findBtn(w, '保存').trigger('click') // authConfig 空 + 新建 → else-if 双真
    expect(w.text()).toContain('所选鉴权类型需要填写 authConfig（JSON）')

    const cfg = w.find('textarea.font-mono')
    for (const [badJson, hint] of [
      ['not-json', '非 JSON'],
      ['null', '!parsed'],
      ['[]', '数组'],
      ['{}', '空对象'],
      ['"str"', '非对象']
    ]) {
      await cfg.setValue(badJson)
      await findBtn(w, '保存').trigger('click')
      expect(w.text()).toContain('authConfig 必须是非空 JSON 对象') // hint: hint
      expect(w.text()).toContain('新建 Webhook 配置') // 弹层保持
    }
    expect(api.post).not.toHaveBeenCalled()
  })

  it('新建成功两连：全字段 POST（trim/Number/authConfig 携带）→ 最小表单（空串→null、无 authConfig 键、默认值）', async () => {
    const { api, WebhooksView } = await fresh({ configs: [] })
    const w = await mountView(WebhooksView)

    await findBtn(w, '新建配置').trigger('click')
    await w.find('input[placeholder="如 slack-alerts"]').setValue('  hook-1  ')
    await w.find('input[placeholder="可选，列表优先展示"]').setValue(' Hook 显示 ')
    await w.find('input[placeholder="https://..."]').setValue(' https://h ')
    await w.find('input[placeholder="留空 = 全部环境"]').setValue(' dev ')
    await w.find('input[placeholder="逗号分隔，留空 = 全部事件"]').setValue(' risk_case , block ')
    await w.find('input[placeholder="如 HIGH,CRITICAL，留空 = 全部"]').setValue(' HIGH ')
    await w.find('textarea[placeholder="可选"]').setValue(' 运维通道 ')
    await w.find('.fixed select').setValue('basic')
    await w.find('textarea.font-mono').setValue(' {"token":"t"} ')
    await w.findAll('.fixed input[type="number"]')[0].setValue('60')
    await w.findAll('.fixed input[type="number"]')[1].setValue('0')
    await w.findAll('.fixed input[type="number"]')[2].setValue('500')

    await findBtn(w, '保存').trigger('click')
    await settle()

    expect(api.post).toHaveBeenCalledTimes(1)
    const [url, body] = api.post.mock.calls[0]
    expect(url).toBe('/api/webhooks/game/g1/configs')
    expect(body).toEqual({
      name: 'hook-1', // 两端 trim
      displayName: 'Hook 显示',
      webhookUrl: 'https://h',
      environmentId: 'dev',
      description: '运维通道',
      eventTypes: 'risk_case , block', // 仅整体 trim，不逐项归一
      riskLevels: 'HIGH',
      authType: 'basic',
      authConfig: '{"token":"t"}', // trim 后非空 → 携带
      timeoutSeconds: 60, // Number 归一
      maxRetries: 0,
      retryBackoffMs: 500
    })
    await waitFor(() => expect(w.text()).toContain('配置「hook-1」已创建'))
    await waitFor(() => {
      expect(w.text()).not.toContain('新建 Webhook 配置') // 弹层关闭
      expect(callsOf(api, '/webhooks/game/')).toHaveLength(2) // 空配置首载 + 成功后重载 = 2
    })

    // 重开：表单重置（emptyForm）
    await findBtn(w, '新建配置').trigger('click')
    expect(w.find('input[placeholder="如 slack-alerts"]').element.value).toBe('')
    expect(w.find('.fixed select').element.value).toBe('none')
    expect(w.findAll('.fixed input[type="number"]').map((i) => i.element.value)).toEqual(['30', '3', '1000'])
    expect(w.find('textarea.font-mono').exists()).toBe(false)

    // 最小表单：可选全空 → ''→null、无 authConfig 键、Number 默认
    await w.find('input[placeholder="如 slack-alerts"]').setValue('hook-2')
    await w.find('input[placeholder="https://..."]').setValue('https://h2')
    await findBtn(w, '保存').trigger('click')
    await settle()

    expect(api.post).toHaveBeenCalledTimes(2)
    const body2 = api.post.mock.calls[1][1]
    expect(body2).toEqual({
      name: 'hook-2', displayName: null, webhookUrl: 'https://h2',
      environmentId: null, description: null, eventTypes: null, riskLevels: null,
      authType: 'none', timeoutSeconds: 30, maxRetries: 3, retryBackoffMs: 1000
    })
    expect(body2.authConfig).toBeUndefined() // none + 空 → 不携带
    await waitFor(() => expect(w.text()).toContain('配置「hook-2」已创建'))
  })

  it('编辑：slack 全值回填（?? 左臂）+ bearer secret 携带 PUT；稀疏脏数据回填（||/?? 全 false 侧）', async () => {
    const { api, WebhooksView } = await fresh()
    const w = await mountView(WebhooksView)

    // 编辑全值行
    await rowBtn(w, 'Slack 告警', '编辑').trigger('click')
    expect(w.text()).toContain('编辑配置：Slack 告警')
    expect(w.find('input[placeholder="如 slack-alerts"]').element.value).toBe('slack-alerts')
    expect(w.find('input[placeholder="可选，列表优先展示"]').element.value).toBe('Slack 告警')
    expect(w.find('input[placeholder="https://..."]').element.value).toBe('https://hooks.example.com/x')
    expect(w.find('input[placeholder="留空 = 全部环境"]').element.value).toBe('prod')
    expect(w.find('input[placeholder="如 HIGH,CRITICAL，留空 = 全部"]').element.value).toBe('HIGH')
    expect(w.findAll('.fixed input[type="number"]').map((i) => i.element.value)).toEqual(['30', '3', '1000']) // ?? 左
    expect(w.find('.fixed select').element.value).toBe('none') // authType 'none' → || 左

    await w.find('.fixed select').setValue('bearer')
    await w.find('textarea.font-mono').setValue('{"token":"edit"}')
    await findBtn(w, '保存').trigger('click')
    await settle()

    expect(api.put).toHaveBeenCalledTimes(1)
    const [purl, pbody] = api.put.mock.calls[0]
    expect(purl).toBe('/api/webhooks/game/g1/configs/w1')
    expect(pbody.authType).toBe('bearer')
    expect(pbody.authConfig).toBe('{"token":"edit"}')
    expect(pbody.displayName).toBe('Slack 告警')
    expect(pbody.timeoutSeconds).toBe(30)
    await waitFor(() => expect(w.text()).toContain('配置「slack-alerts」已更新')) // 成功文案用表单 name（源码 submitForm 取 f.name.trim()）

    // 编辑稀疏脏数据：name/url 空串、authType null、超时三键 null → 全部 false 侧回填
    await rowBtn(w, '稀疏配置', '编辑').trigger('click')
    expect(w.text()).toContain('编辑配置：稀疏配置') // displayName || name → displayName
    expect(w.find('input[placeholder="如 slack-alerts"]').element.value).toBe('') // name || '' false 侧
    expect(w.find('input[placeholder="https://..."]').element.value).toBe('') // url || '' false 侧
    expect(w.find('.fixed select').element.value).toBe('none') // authType || 'none' false 侧
    expect(w.findAll('.fixed input[type="number"]').map((i) => i.element.value)).toEqual(['30', '3', '1000']) // ?? 右

    await findBtn(w, '取消').trigger('click')
    expect(w.text()).not.toContain('编辑配置：稀疏配置')
  })

  it('编辑 audit：secret 不回显（留空=保留，PUT 无 authConfig 键）、无星标、编辑态空 authConfig 放行、文案回落 name', async () => {
    const { api, WebhooksView } = await fresh()
    const w = await mountView(WebhooksView)

    await rowBtn(w, 'audit-sink', '编辑').trigger('click')
    expect(w.text()).toContain('编辑配置：audit-sink') // displayName null → name
    expect(w.find('textarea.font-mono').attributes('placeholder')).toBe('已设置，留空保持不变') // 编辑占位臂
    expect(w.find('textarea.font-mono').element.value).toBe('') // secret 不回显
    const labels = w.findAll('label')
    const authLabel = labels.find((l) => l.text().includes('鉴权配置'))
    expect(authLabel.text()).not.toContain('*') // !editing 假 → 无星标
    expect(w.find('.fixed select').element.value).toBe('basic')

    await findBtn(w, '保存').trigger('click') // authType basic + 空 config + 编辑 → else-if 右假放行
    await settle()

    expect(api.put).toHaveBeenCalledTimes(1)
    const [, pbody] = api.put.mock.calls[0]
    expect(pbody.name).toBe('audit-sink')
    expect(pbody.authConfig).toBeUndefined() // 留空 = 保留原值
    expect(pbody.displayName).toBeNull()
    expect(pbody.environmentId).toBeNull()
    expect(pbody.eventTypes).toBeNull()
    expect(pbody.timeoutSeconds).toBe(30) // ?? 右
    await waitFor(() => expect(w.text()).toContain('配置「audit-sink」已更新'))
  })

  it('保存失败 4 变体（saving busy 中态先行）：message / ?.data / ?. / 兜底', async () => {
    const { api, WebhooksView } = await fresh({ configs: [] })
    const w = await mountView(WebhooksView)

    await findBtn(w, '新建配置').trigger('click')
    await w.find('input[placeholder="如 slack-alerts"]').setValue('dup')
    await w.find('input[placeholder="https://..."]').setValue('https://h')

    // 第一变体挂起：saving busy 中态可断言
    const pend = deferred()
    api.post.mockImplementationOnce(() => pend.p)
    await findBtn(w, '保存').trigger('click')
    expect(w.text()).toContain('保存中...')
    expect(findBtn(w, '取消').attributes('disabled')).toBeDefined()
    pend.reject(CHAIN_VARIANTS[0][0])
    await settle()
    expect(w.text()).toContain('后端拒绝了') // formError 弹层内
    expect(w.text()).toContain('新建 Webhook 配置') // 弹层保持
    expect(w.text()).not.toContain('保存中...') // saving 复位

    for (const [variant] of CHAIN_VARIANTS.slice(1)) {
      api.post.mockRejectedValueOnce(variant)
      await findBtn(w, '保存').trigger('click')
      await settle()
      expect(w.text()).toContain('保存失败')
    }
    expect(api.post).toHaveBeenCalledTimes(4)
  })

  it('测试发送成功：busy 中态、结果卡（HTTP/耗时/logId）、刷新清卡', async () => {
    const { api, WebhooksView } = await fresh()
    const w = await mountView(WebhooksView)

    const pend = deferred()
    api.post.mockImplementation((url) => (url.includes('/webhooks/test/') ? pend.p : ok({})))

    await rowBtn(w, 'Slack 告警', '测试').trigger('click')
    expect(w.text()).toContain('发送中...')
    expect(rowBtn(w, 'Slack 告警', '发送中...').attributes('disabled')).toBeDefined()
    expect(api.post).toHaveBeenCalledWith('/api/webhooks/test/w1', null, { params: { gameId: 'g1' } })

    pend.resolve({ data: { status: 'success', httpStatus: 200, responseTimeMs: 120, logId: 77 } })
    await settle()
    await waitFor(() => {
      expect(w.text()).toContain('测试「Slack 告警」')
      expect(w.text()).toContain('成功')
      expect(w.text()).toContain('HTTP 200，耗时 120ms')
      expect(w.text()).toContain('（日志 77）')
    })
    expect(rowFor(w, 'Slack 告警', '') && w.find('.card.text-sm').exists()).toBe(true)

    await findBtn(w, '刷新').trigger('click') // load() 清 testResult
    await settle()
    expect(w.text()).not.toContain('测试「Slack 告警」')
  })

  it('测试结果卡失败渲染：status!=success、errorType/error、logId 缺省、configName 回落 name', async () => {
    const { api, WebhooksView } = await fresh()
    const w = await mountView(WebhooksView)

    api.post.mockImplementation((url) =>
      url.includes('/webhooks/test/')
        ? ok({ status: 'failed', errorType: 'TimeoutError', error: 'connect timeout' })
        : ok({}))
    await rowBtn(w, 'audit-sink', '测试').trigger('click')
    await settle()

    await waitFor(() => {
      expect(w.text()).toContain('测试「audit-sink」') // displayName null → name
      expect(w.text()).toContain('失败')
      expect(w.text()).toContain('TimeoutError: connect timeout')
      expect(w.text()).not.toContain('（日志') // logId 缺省臂
    })
  })

  it('测试失败 4 变体：message / ?.data / ?. / 兜底进横幅', async () => {
    const { api, WebhooksView } = await fresh()
    const w = await mountView(WebhooksView)

    for (const [variant, expectMsg] of CHAIN_VARIANTS) {
      api.post.mockRejectedValueOnce(variant)
      await rowBtn(w, 'Slack 告警', '测试').trigger('click')
      await settle()
      await waitFor(() => expect(w.text()).toContain(expectMsg || '测试发送失败'))
    }
  })

  it('启停双向：PUT {...config,status} 与成功文案双侧；失败 4 变体', async () => {
    const { api, WebhooksView } = await fresh()
    const w = await mountView(WebhooksView)

    await rowBtn(w, 'Slack 告警', '停用').trigger('click')
    await settle()
    expect(api.put).toHaveBeenCalledWith('/api/webhooks/game/g1/configs/w1', { ...CFG_ACTIVE, status: 'INACTIVE' })
    await waitFor(() => expect(w.text()).toContain('配置「Slack 告警」已停用'))

    await rowBtn(w, 'audit-sink', '启用').trigger('click')
    await settle()
    expect(api.put).toHaveBeenCalledWith('/api/webhooks/game/g1/configs/w2', { ...CFG_BARE, status: 'ACTIVE' })
    await waitFor(() => expect(w.text()).toContain('配置「audit-sink」已启用')) // displayName null → name

    for (const [variant, expectMsg] of CHAIN_VARIANTS) {
      api.put.mockRejectedValueOnce(variant)
      await rowBtn(w, 'Slack 告警', '停用').trigger('click') // 重载恢复 fixture → 恒 ACTIVE
      await settle()
      await waitFor(() => expect(w.text()).toContain(expectMsg || '状态更新失败'))
    }
  })

  it('删除：confirm 门控与文案双侧（参数直断）、成功双侧、失败 4 变体', async () => {
    const no = vi.fn(() => false)
    vi.stubGlobal('confirm', no)
    const { api, WebhooksView } = await fresh()
    const w = await mountView(WebhooksView)

    await rowBtn(w, 'Slack 告警', '删除').trigger('click')
    expect(api.delete).not.toHaveBeenCalled()
    expect(no).toHaveBeenCalledWith(expect.stringContaining('「Slack 告警」')) // displayName 侧

    const yes = vi.fn(() => true)
    vi.stubGlobal('confirm', yes)
    api.delete.mockResolvedValueOnce({ data: {} })
    await rowBtn(w, 'Slack 告警', '删除').trigger('click')
    await settle()
    expect(api.delete).toHaveBeenCalledWith('/api/webhooks/game/g1/configs/w1')
    await waitFor(() => expect(w.text()).toContain('配置「Slack 告警」已删除'))

    await rowBtn(w, 'audit-sink', '删除').trigger('click')
    await settle()
    expect(yes).toHaveBeenCalledWith(expect.stringContaining('「audit-sink」')) // name 回落侧
    await waitFor(() => expect(w.text()).toContain('配置「audit-sink」已删除'))

    for (const [variant, expectMsg] of CHAIN_VARIANTS) {
      api.delete.mockRejectedValueOnce(variant)
      await rowBtn(w, 'Slack 告警', '删除').trigger('click')
      await settle()
      await waitFor(() => expect(w.text()).toContain(expectMsg || '删除失败'))
    }
  })

  it('日志弹层：loading 中态 → 列表全字段（??/!= null/|| 全双侧、投递色）→ 关闭按钮与名称回落', async () => {
    const { WebhooksView, pending } = await fresh({ slowLogs: true })
    const w = await mountView(WebhooksView)

    await rowBtn(w, 'Slack 告警', '日志').trigger('click')
    expect(w.text()).toContain('发送日志：Slack 告警')
    expect(w.text()).toContain('加载中...') // logsLoading 臂

    pending.logs.resolve()
    await settle()
    await waitFor(() => expect(w.findAll('.fixed tbody tr')).toHaveLength(3))
    const rows = w.findAll('.fixed tbody tr')

    const r1 = rows[0].text()
    expect(r1).toContain('2026-09-30 08:00:00')
    expect(r1).toContain('risk_case')
    expect(r1).toContain('200') // responseStatus 有值
    expect(r1).toContain('95ms')
    expect(r1).toContain('×0')
    expect(r1).not.toContain('connection') // errorMessage '' → '-'
    expect(rows[0].findAll('span').find((s) => s.text() === 'SUCCESS').classes().join(' ')).toContain('bg-green-100')

    const r2 = rows[1].text()
    expect(r2).toContain('-') // sentAt null
    expect(r2).toContain('block')
    expect(r2).toContain('connection refused')
    expect(r2).toContain('×2')
    expect(rows[1].findAll('span').find((s) => s.text() === 'FAILED').classes().join(' ')).toContain('bg-red-100')

    const r3 = rows[2].text()
    expect(r3).toContain('503')
    expect(r3).toContain('×0') // retryCount undefined → ?? 0
    expect(r3).toContain('-') // responseTimeMs null / errorMessage null
    expect(rows[2].findAll('span').find((s) => s.text() === 'RETRYING').classes().join(' ')).toContain('bg-yellow-100')

    await findBtn(w, '关闭').trigger('click')
    expect(w.text()).not.toContain('发送日志：')

    await rowBtn(w, 'audit-sink', '日志').trigger('click')
    await settle()
    expect(w.text()).toContain('发送日志：audit-sink') // displayName null → name
  })

  it('日志空态与失败 4 变体；log 遮罩 @click.self 关闭', async () => {
    const { api, WebhooksView } = await fresh({ logs: [] })
    const w = await mountView(WebhooksView)

    await rowBtn(w, 'Slack 告警', '日志').trigger('click')
    await settle()
    expect(w.text()).toContain('暂无发送记录') // 非 loading 且空 → else 臂

    for (const [variant, expectMsg] of CHAIN_VARIANTS) {
      const route = (url) => {
        if (url === '/api/games') return ok({ content: [{ id: 'g1' }] })
        if (url.includes('/webhooks/logs/')) return bad(variant)
        if (url.includes('/webhooks/stats/')) return ok(STATS)
        return ok([CFG_ACTIVE, CFG_BARE, CFG_SPARSE])
      }
      api.get.mockImplementation(route)
      await rowBtn(w, 'Slack 告警', '日志').trigger('click')
      await settle()
      await waitFor(() => expect(w.text()).toContain(expectMsg || '加载发送日志失败'))
      expect(w.text()).toContain('发送日志：Slack 告警') // 弹层保持
    }

    await w.find('.fixed').trigger('click') // @click.self
    expect(w.text()).not.toContain('发送日志：')
  })
})
