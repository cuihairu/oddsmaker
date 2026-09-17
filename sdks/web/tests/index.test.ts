/**
 * Oddsmaker Web SDK 源码直测(node:test)。
 * 运行:npm run test:src(node >= 22 直接执行 .ts,coverage 映射 TS 源行号)。
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { gunzipSync } from 'node:zlib';

import {
  Oddsmaker,
  hash32, assignVariant,
  fetchExperiments, assignAllAndExpose, assignAllWithTargeting,
  versionGte, versionLte, matchTargeting,
  getCachedExperiments, fetchExperimentsCached,
  startExperimentsAutoRefresh, ensureFreshExperimentsAndAssign,
} from '../src/index.ts';

const sleep = (ms: number) => new Promise(r => setTimeout(r, ms));

// ---------- 测试环境:window / fetch / CompressionStream / document 可编程替身 ----------

type FetchStep = { status?: number; headers?: Record<string, string>; body?: string; throw?: Error };

class Env {
  store = new Map<string, string>();
  writes: Array<[string, string]> = [];
  listeners = new Map<string, Array<() => void>>();
  fetchCalls: Array<{ url: string; init: any }> = [];
  debugs: string[] = [];
  warns: string[] = [];
  private fetchSteps: FetchStep[] = [];
  private fetchCount = 0;
  private saved: any = {};

  install(fetchSteps: FetchStep[] = [{ status: 200 }], opts: { compression?: false | 'throw' } = {}) {
    this.fetchSteps = fetchSteps;
    this.saved = {
      window: (globalThis as any).window,
      fetch: (globalThis as any).fetch,
      CS: (globalThis as any).CompressionStream,
      document: (globalThis as any).document,
      debug: console.debug, warn: console.warn,
    };
    const env = this;
    (globalThis as any).window = {
      localStorage: {
        getItem: (k: string) => (env.store.has(k) ? env.store.get(k)! : null),
        setItem: (k: string, v: string) => { env.store.set(k, v); env.writes.push([k, v]); },
      },
      addEventListener: (type: string, fn: () => void) => {
        const arr = env.listeners.get(type) || [];
        arr.push(fn); env.listeners.set(type, arr);
      },
    };
    (globalThis as any).fetch = async (url: any, init: any) => {
      env.fetchCalls.push({ url: String(url), init });
      const s = env.fetchSteps[Math.min(env.fetchCount++, env.fetchSteps.length - 1)];
      if (s.throw) throw s.throw;
      return new Response(s.body ?? null, { status: s.status ?? 200, headers: s.headers ?? {} });
    };
    if (opts.compression === false) (globalThis as any).CompressionStream = undefined;
    else if (opts.compression === 'throw')
      (globalThis as any).CompressionStream = class { constructor() { throw new Error('gzip unavailable'); } };
    console.debug = (...a: any[]) => { env.debugs.push(a.map(String).join(' ')); };
    console.warn = (...a: any[]) => { env.warns.push(a.map(String).join(' ')); };
  }

  /** 同一用例内重置 fetch 脚本。 */
  resetFetch(steps: FetchStep[]) { this.fetchSteps = steps; this.fetchCount = 0; }

  restore() {
    (globalThis as any).window = this.saved.window;
    (globalThis as any).fetch = this.saved.fetch;
    (globalThis as any).CompressionStream = this.saved.CS;
    (globalThis as any).document = this.saved.document;
    console.debug = this.saved.debug; console.warn = this.saved.warn;
  }

  emit(type: string) { for (const fn of this.listeners.get(type) || []) fn(); }

  opts(over: Record<string, any> = {}) {
    return { apiKey: 'k', endpoint: 'https://ing.example/', gameId: 'g1', environment: 'prod', deviceId: 'dev1', ...over };
  }

  qkey(deviceId = 'dev1') { return 'oddsmaker_queue_g1_prod_' + deviceId; }

  /** 最后一次队列持久化里的事件列表(入队顺序)。 */
  queued(deviceId = 'dev1'): any[] {
    const w = [...this.writes].reverse().find(([k]) => k === this.qkey(deviceId));
    return w ? JSON.parse(w[1]) : [];
  }
}

function setup(t: { after: (fn: () => void) => void }, fetchSteps?: FetchStep[], installOpts: { compression?: false | 'throw' } = {}) {
  const env = new Env();
  env.install(fetchSteps, installOpts);
  t.after(() => env.restore());
  return env;
}

function makeSdk(t: { after: (fn: () => void) => void }, env: Env, over: Record<string, any> = {}) {
  const sdk = new Oddsmaker(env.opts(over));
  t.after(() => sdk.shutdown());
  return sdk;
}

// ---------- 构造与 deviceId ----------

test('deviceId 三级链:opts 优先 → localStorage → 随机生成并回写', async t => {
  // 1. opts.deviceId 显式给出
  const env = setup(t);
  const sdk = makeSdk(t, env);
  sdk.track('a');
  const evs = env.queued();
  assert.equal(evs[0].device_id, 'dev1');
  assert.equal(env.store.get('oddsmaker_device_id_g1_prod'), 'dev1');   // 回写

  // 2. 无 opts,localStorage 命中
  const env2 = setup(t);
  env2.store.set('oddsmaker_device_id_g1_prod', 'dev_saved');
  const sdk2 = makeSdk(t, env2, { deviceId: undefined });
  sdk2.track('a');
  assert.equal(env2.queued('dev_saved')[0].device_id, 'dev_saved');

  // 3. 都没有 → d_ 前缀随机
  const env3 = setup(t);
  const sdk3 = makeSdk(t, env3, { deviceId: undefined });
  sdk3.track('a');
  const random = env3.store.get('oddsmaker_device_id_g1_prod')!;
  assert.match(random, /^d_/);
  assert.equal(env3.queued(random)[0].device_id, random);
});

test('localStorage 读取抛错时静默降级(getItem 抛 → 随机 deviceId)', async t => {
  const env = setup(t);
  (globalThis.window as any).localStorage.getItem = () => { throw new Error('denied'); };
  const sdk = makeSdk(t, env, { deviceId: undefined });
  sdk.track('a');   // persistQueue 的 storageSet 正常,可读回
  const random = env.store.get('oddsmaker_device_id_g1_prod')!;
  assert.match(random, /^d_/);
  assert.equal(env.queued(random).length, 1);
});

test('setItem 抛错时持久化静默(track 正常返回)', async t => {
  const env = setup(t);
  (globalThis.window as any).localStorage.setItem = () => { throw new Error('quota'); };
  const sdk = makeSdk(t, env);
  const id = sdk.track('a');
  assert.ok(id);
  assert.equal(env.writes.length, 0);
});

test('恢复持久化队列:好 JSON 入队;坏 JSON 忽略', async t => {
  const env = setup(t);
  env.store.set(env.qkey(), JSON.stringify([
    { event_id: 'restored', game_id: 'g1', environment: 'prod', event_type: 'business', event_name: 'r', device_id: 'dev1', ts_client: 1 },
  ]));
  const sdk = makeSdk(t, env);
  sdk.track('new');
  const evs = env.queued();
  assert.equal(evs.length, 2);
  assert.equal(evs[0].event_id, 'restored');

  const env2 = setup(t);
  env2.store.set(env2.qkey(), '{not json');
  const sdk2 = makeSdk(t, env2);
  sdk2.track('new');
  assert.equal(env2.queued().length, 1);
});

// ---------- 用户态与事件封装 ----------

test('setUserId/setUserProps/setPlayer 合入后续事件', async t => {
  const env = setup(t);
  const sdk = makeSdk(t, env);
  sdk.setUserId('u1');
  sdk.setUserProps({ tier: 'gold', vip: 2 });
  sdk.setPlayer('p9');
  sdk.track('shop_buy');
  sdk.setUserId(null);
  sdk.track('another');
  const evs = env.queued();
  assert.equal(evs[0].user_id, 'u1');
  assert.equal(evs[0].player_id, 'p9');
  assert.equal(evs[0].props.tier, 'gold');
  assert.equal(evs[0].props.vip, 2);
  assert.equal(evs[0].props.player_id, 'p9');
  assert.equal(evs[0].platform, 'web');
  assert.equal(evs[0].game_id, 'g1');
  assert.equal(evs[0].environment, 'prod');
  assert.equal(evs[0].event_type, 'business');
  assert.ok(evs[0].ts_client > 0);
  assert.match(evs[0].event_id, /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[0-9a-f]{4}-[0-9a-f]{12}$/);   // version=7 的 uuidv7(四端同构)
  assert.equal(evs[1].user_id, undefined);   // setUserId(null)
  assert.equal(evs[1].props.player_id, 'p9');
});

test('identify:首次无 previous / 换号带 previous / 同号无 previous / player 与 props 合入', async t => {
  const env = setup(t);
  const sdk = makeSdk(t, env);
  sdk.identify('u1');
  sdk.setPlayer('p9');
  sdk.identify('u2', { tier: 'gold' });
  sdk.identify('u2');
  const evs = env.queued();
  assert.equal(evs[0].event_name, '$identify');
  assert.equal(evs[0].event_type, 'identity');
  assert.equal(evs[0].props.previous_user_id, undefined);
  assert.equal(evs[1].props.previous_user_id, 'u1');
  assert.equal(evs[1].props.new_user_id, 'u2');
  assert.equal(evs[1].props.$identify, true);
  assert.equal(evs[1].props.player_id, 'p9');
  assert.equal(evs[1].props.tier, 'gold');
  assert.equal(evs[1].user_id, 'u2');
  assert.equal(evs[2].props.previous_user_id, undefined);
});

test('rollSession:同会话延续;超过 sessionGap 换新 session', async t => {
  const env = setup(t);
  const sdk = makeSdk(t, env);
  sdk.track('a');
  sdk.track('b');
  const s1 = env.queued().map(e => e.session_id);
  assert.ok(s1[0]);
  assert.equal(s1[1], s1[0]);   // 间隙远小于默认 gap → 同 session

  const env2 = setup(t);
  const sdk2 = makeSdk(t, env2, { sessionGapMs: 0 });
  sdk2.track('a');
  await sleep(3);
  sdk2.track('b');
  const s2 = env2.queued().map(e => e.session_id);
  assert.ok(s2[0]);
  assert.notEqual(s2[1], s2[0]);   // gap=0 → 必换新 session
});

test('inferEventType 全部 8 类分支', async t => {
  const env = setup(t);
  const sdk = makeSdk(t, env);
  const names = ['$identify', 'identity_login', 'risk_hit', 'fraud_check', 'experiment_view',
    'ad_click', 'level_up', 'quest_done', 'session_start', 'error_bad', 'crash_now', 'shop_buy'];
  for (const n of names) sdk.track(n);
  const types = env.queued().map(e => e.event_type);
  assert.deepEqual(types, ['identity', 'identity', 'risk', 'risk', 'experiment',
    'ad', 'progression', 'progression', 'session', 'error', 'error', 'business']);
});

// ---------- 业务 helper 字段断言 ----------

test('helper:tutorial / level 三连(含 game_mode 提取)', async t => {
  const env = setup(t);
  const sdk = makeSdk(t, env);
  sdk.tutorialStart('t1');
  sdk.tutorialComplete('t1');
  sdk.levelStart(7, { game_mode: 'hard' });
  sdk.levelFail(7, 'timeout', { game_mode: 'normal' });
  sdk.levelComplete('7');
  const evs = env.queued();
  assert.equal(evs[0].event_name, 'tutorial_start');
  assert.equal(evs[0].props.tutorial_id, 't1');
  assert.equal(evs[1].props.tutorial_id, 't1');
  assert.equal(evs[2].event_name, 'level_start');
  assert.equal(evs[2].level_id, '7');
  assert.equal(evs[2].game_mode, 'hard');
  assert.equal(evs[2].props.level_id, '7');
  assert.equal(evs[2].event_type, 'progression');
  assert.equal(evs[3].level_id, '7');
  assert.equal(evs[3].game_mode, 'normal');
  assert.equal(evs[3].props.fail_reason, 'timeout');
  assert.equal(evs[4].level_id, '7');
  assert.equal(evs[4].game_mode, undefined);   // stringProp 非字符串 → undefined
});

test('helper:货币/物品 流向字段', async t => {
  const env = setup(t);
  const sdk = makeSdk(t, env);
  sdk.currencySource('gold', 10);
  sdk.currencySink('gold', 3);
  sdk.itemGrant('sword');
  sdk.itemConsume('sword', 2);
  const evs = env.queued();
  const cs = evs[0];
  assert.equal(cs.event_name, 'currency_source');
  assert.equal(cs.resource_id, 'GOLD');
  assert.equal(cs.resource_amount, 10);
  assert.equal(cs.virtual_currency, 'GOLD');
  assert.equal(cs.virtual_amount, 10);
  assert.equal(cs.flow_type, 'source');
  assert.equal(cs.props.currency_code, 'GOLD');
  assert.equal(cs.props.amount, 10);
  const ci = evs[1];
  assert.equal(ci.flow_type, 'sink');
  assert.equal(ci.resource_amount, 3);
  const ig = evs[2];
  assert.equal(ig.item_id, 'sword');
  assert.equal(ig.resource_id, 'sword');
  assert.equal(ig.resource_amount, 1);   // quantity 默认 1
  assert.equal(ig.flow_type, 'source');
  assert.equal(ig.props.item_id, 'sword');
  assert.equal(ig.props.quantity, 1);
  const ic = evs[3];
  assert.equal(ic.flow_type, 'sink');
  assert.equal(ic.resource_amount, 2);
});

test('helper:收入类(revenue/iap/webshop/adImpression/rewardedAd)', async t => {
  const env = setup(t);
  const sdk = makeSdk(t, env);
  sdk.revenue(3, 'eur');
  sdk.iapOrder('o1', 9.99, 'usd', { product_id: 'p1' });
  sdk.webshopOrder('o2', 5, 'usd');
  sdk.adImpression(0.5, 'usd', { network: 'adm', placement_id: 'plc', ad_format: 'banner' });
  sdk.rewardedAdComplete('adm', 'unit1');
  const evs = env.queued();
  const r = evs[0];
  assert.equal(r.event_name, 'revenue');
  assert.equal(r.revenue_amount, 3);
  assert.equal(r.revenue_currency, 'EUR');
  assert.equal(r.props.amount, 3);
  assert.equal(r.props.currency, 'EUR');
  const iap = evs[1];
  assert.equal(iap.event_name, 'iap_order');
  assert.equal(iap.revenue_amount, 9.99);
  assert.equal(iap.revenue_currency, 'USD');
  assert.equal(iap.order_id, 'o1');
  assert.equal(iap.product_id, 'p1');
  assert.equal(iap.props.order_id, 'o1');
  const ws = evs[2];
  assert.equal(ws.order_id, 'o2');
  assert.equal(ws.product_id, undefined);
  assert.equal(ws.revenue_amount, 5);
  const ad = evs[3];
  assert.equal(ad.event_name, 'ad_impression');
  assert.equal(ad.event_type, 'ad');   // 名字含 ad_
  assert.equal(ad.ad_network, 'adm');
  assert.equal(ad.ad_placement, 'plc');
  assert.equal(ad.ad_format, 'banner');
  assert.equal(ad.revenue_amount, 0.5);
  const rad = evs[4];
  assert.equal(rad.event_name, 'rewarded_ad_complete');
  assert.equal(rad.props.network, 'adm');
  assert.equal(rad.props.ad_unit_id, 'unit1');
});

test('helper:运营/社交/异常类', async t => {
  const env = setup(t);
  const sdk = makeSdk(t, env);
  sdk.expose('exp1', 'B');
  sdk.eventEntry('live1');
  sdk.eventRewardClaim('live1', 'r1');
  sdk.guildJoin('g9');
  sdk.inviteSent('wechat');
  sdk.crash('NRE');
  sdk.fpsDrop(12);
  sdk.networkTimeout('/api/x');
  sdk.cheatFlag('speed', 'high');
  const evs = env.queued();
  assert.equal(evs[0].event_name, 'experiment_exposure');
  assert.equal(evs[0].props.exp, 'exp1');
  assert.equal(evs[0].props.variant, 'B');
  assert.equal(evs[0].event_type, 'experiment');
  assert.equal(evs[1].props.liveops_event_id, 'live1');
  assert.equal(evs[2].props.reward_id, 'r1');
  assert.equal(evs[3].event_name, 'guild_join');
  assert.equal(evs[3].guild_id, 'g9');
  assert.equal(evs[4].props.channel, 'wechat');
  assert.equal(evs[5].event_type, 'error');
  assert.equal(evs[5].props.error_name, 'NRE');
  assert.equal(evs[6].props.fps, 12);
  assert.equal(evs[7].props.endpoint, '/api/x');
  assert.equal(evs[8].event_type, 'business');   // cheat_flag 不命中任何前缀规则
});

// ---------- 发送:flush / send 全分支 ----------

test('自动 flush:size 达 maxBatch', async t => {
  const env = setup(t, [{ status: 200 }]);
  const sdk = makeSdk(t, env, { maxBatch: 2 });
  sdk.track('a');
  assert.equal(env.fetchCalls.length, 0);
  sdk.track('b');
  await sleep(20);
  assert.equal(env.fetchCalls.length, 1);
});

test('自动 flush:字节超 maxQueueBytes(overLimit)', async t => {
  const env = setup(t, [{ status: 200 }]);
  const sdk = makeSdk(t, env, { maxQueueBytes: 10 });
  sdk.track('big');   // 单条 JSON 远超 10 字节
  await sleep(20);
  assert.equal(env.fetchCalls.length, 1);
});

test('flush:空队列直接返回(不 fetch)', async t => {
  const env = setup(t);
  const sdk = makeSdk(t, env);
  await sdk.flush();
  assert.equal(env.fetchCalls.length, 0);
});

test('send 成功:真 gzip(body 1f 8b)+ 头 + URL(尾斜杠剥离)+ debug 日志', async t => {
  const env = setup(t, [{ status: 200 }]);
  const sdk = makeSdk(t, env, { maxBatch: 1, debug: true });
  sdk.track('boot');
  await sleep(30);
  assert.equal(env.fetchCalls.length, 1);
  const c = env.fetchCalls[0];
  assert.equal(c.url, 'https://ing.example/v1/batch');   // 构造器剥掉尾斜杠
  assert.equal(c.init.method, 'POST');
  assert.equal(c.init.headers['x-api-key'], 'k');
  assert.equal(c.init.headers['content-type'], 'application/x-ndjson');
  assert.equal(c.init.headers['content-encoding'], 'gzip');
  const raw = Buffer.from(c.init.body);
  assert.equal(raw[0], 0x1f);
  assert.equal(raw[1], 0x8b);
  const events = gunzipSync(raw).toString().trim().split('\n').map(JSON.parse);   // 真解压
  assert.equal(events.length, 1);
  assert.equal(events[0].event_name, 'boot');
  assert.ok(env.debugs.some(d => d.includes('flushed')));   // debug 分支
});

test('send 成功:无 CompressionStream → 明文 ndjson', async t => {
  const env = setup(t, [{ status: 200 }], { compression: false });
  const sdk = makeSdk(t, env, { maxBatch: 1 });
  sdk.track('plain');
  await sleep(20);
  const c = env.fetchCalls[0];
  assert.equal(c.init.headers['content-encoding'], undefined);
  assert.equal(typeof c.init.body, 'string');
  assert.equal(JSON.parse(c.init.body).event_name, 'plain');
});

test('send:CompressionStream 构造抛错 → 降级明文', async t => {
  const env = setup(t, [{ status: 200 }], { compression: 'throw' });
  const sdk = makeSdk(t, env, { maxBatch: 1 });
  sdk.track('fallback');
  await sleep(20);
  const c = env.fetchCalls[0];
  assert.equal(c.init.headers['content-encoding'], undefined);
  assert.equal(JSON.parse(c.init.body).event_name, 'fallback');
});

test('send:429 按 retry-after=0 立即重试后成功', async t => {
  const env = setup(t, [
    { status: 429, headers: { 'retry-after': '0' } },
    { status: 200 },
  ]);
  const sdk = makeSdk(t, env, { maxBatch: 1 });
  sdk.track('retry');
  await sleep(30);
  assert.equal(env.fetchCalls.length, 2);   // 两次尝试都发出
});

test('send:HTTP 500 → 退避重试后成功', async t => {
  const env = setup(t, [{ status: 500 }, { status: 200 }]);
  const sdk = makeSdk(t, env, { maxBatch: 1 });
  sdk.track('err500');
  await sleep(1400);   // 首次退避 ~1000ms
  assert.equal(env.fetchCalls.length, 2);
});

test('send:五次尝试全失败 → 队列还原并持久化(离线缓存)', async t => {
  const env = setup(t, [
    { status: 429, headers: { 'retry-after': '0' } },
    { status: 429, headers: { 'retry-after': '0' } },
    { status: 429, headers: { 'retry-after': '0' } },
    { throw: new TypeError('net down') },
    { throw: new TypeError('net down') },
  ]);
  const sdk = makeSdk(t, env, { maxBatch: 1, debug: true });
  sdk.track('lost');
  await sleep(1500);   // attempt4 失败后一次 ~1000ms 退避
  assert.equal(env.fetchCalls.length, 5);
  const restored = env.queued();
  assert.equal(restored.length, 1);
  assert.equal(restored[0].event_name, 'lost');
  assert.ok(env.warns.some(w => w.includes('flush failed')));   // debug 分支
});

test('window 事件:online → flush;visibilitychange hidden → flush;visible → 不动', async t => {
  const env = setup(t, [{ status: 200 }]);
  const sdk = makeSdk(t, env);
  sdk.track('x');
  assert.equal(env.fetchCalls.length, 0);

  (globalThis as any).document = { visibilityState: 'visible' };
  env.emit('visibilitychange');
  await sleep(10);
  assert.equal(env.fetchCalls.length, 0);   // visible 不触发

  (globalThis as any).document = { visibilityState: 'hidden' };
  env.emit('visibilitychange');
  await sleep(20);
  assert.equal(env.fetchCalls.length, 1);   // hidden → flush

  env.emit('online');   // 队列已空 → flush 早退
  await sleep(10);
  assert.equal(env.fetchCalls.length, 1);
});

test('shutdown 清掉定时器,可重复调用', async t => {
  const env = setup(t);
  const sdk = makeSdk(t, env);
  sdk.track('a');
  sdk.shutdown();
  sdk.shutdown();
});

// ---------- 分流纯函数 ----------

test('hash32:跨端一致性向量', () => {
  assert.equal(hash32(''), 0x811c9dc5);
  assert.equal(hash32('a'), 0xe40c292c);
  assert.equal(hash32('foobar'), 0xbf9cf968);
});

test('assignVariant:空/权重 0 兜底/确定性', () => {
  assert.equal(assignVariant({ id: 'e', variants: [] }, 'k'), 'A');
  // weight 全 0:sum=0 → 回落 variants.length;acc 每项按 1 累计
  assert.equal(assignVariant({ id: 'e', salt: 's', variants: [{ name: 'B', weight: 0 }] }, 'k'), 'B');
  const exp = { id: 'x', salt: 's', variants: [{ name: 'A', weight: 50 }, { name: 'B', weight: 50 }] };
  const pick = assignVariant(exp, 'u1');
  assert.ok(['A', 'B'].includes(pick));
  assert.equal(assignVariant(exp, 'u1'), pick);   // 确定性
  assert.ok(['A', 'B'].includes(assignVariant({ id: 'x', variants: exp.variants }, 'u2')));   // salt 缺省
});

test('versionGte/versionLte:数值逐段比较', () => {
  assert.equal(versionGte('2.0.0', '1.9.9'), true);
  assert.equal(versionGte('1.2.3', '1.2.3'), true);
  assert.equal(versionGte('1.2', '1.2.0'), true);   // 缺段补 0
  assert.equal(versionGte('1.2.3', '1.10.0'), false);   // 数值比较,非字典序
  assert.equal(versionGte('1.9', '1.9.1'), false);
  assert.equal(versionGte('1.02.3', '1.2.0'), true);
  assert.equal(versionLte('1.0.0', '1.0.1'), true);
  assert.equal(versionLte('1.1.0', '1.0.1'), false);
});

test('matchTargeting 全分支', () => {
  assert.equal(matchTargeting(undefined, {}), true);
  assert.equal(matchTargeting({}, {}), true);
  assert.equal(matchTargeting({ platform: [] }, { platform: 'web' }), true);   // 空数组不拦
  assert.equal(matchTargeting({ platform: ['web'] }, { platform: 'web' }), true);
  assert.equal(matchTargeting({ platform: ['web'] }, { platform: 'ios' }), false);
  assert.equal(matchTargeting({ platform: ['web'] }, {}), true);   // ctx 未给不拦
  assert.equal(matchTargeting({ appVersionMin: '1.0.0' }, { appVersion: '1.5.0' }), true);
  assert.equal(matchTargeting({ appVersionMin: '2.0.0' }, { appVersion: '1.5.0' }), false);
  assert.equal(matchTargeting({ appVersionMin: '2.0.0' }, {}), true);
  assert.equal(matchTargeting({ appVersionMax: '2.0.0' }, { appVersion: '2.0.0' }), true);
  assert.equal(matchTargeting({ appVersionMax: '1.9.0' }, { appVersion: '2.0.0' }), false);
  assert.equal(matchTargeting({ appVersionMax: '1.9.0' }, {}), true);
  assert.equal(matchTargeting({ countries: ['US', 'ca'] }, { country: 'us' }), true);   // 大小写不敏感
  assert.equal(matchTargeting({ countries: ['JP'] }, { country: 'us' }), false);
  assert.equal(matchTargeting({ countries: ['JP'] }, {}), true);
});

// ---------- 实验配置获取与分流 ----------

test('fetchExperiments:URL 拼接与编码、accept 头、非 2xx 抛错', async t => {
  const env = setup(t, [{ status: 200, body: '[{"id":"e1"}]' }]);
  const exps = await fetchExperiments('https://ctl.example/', 'g 1', 'prod');
  assert.deepEqual(exps, [{ id: 'e1' }]);
  assert.equal(env.fetchCalls[0].url, 'https://ctl.example/api/config/g%201/prod');
  assert.equal(env.fetchCalls[0].init.headers.accept, 'application/json');
  env.resetFetch([{ status: 503 }]);
  await assert.rejects(fetchExperiments('https://c', 'g', 'p'), /failed: 503/);
});

test('assignAllAndExpose:targeting 不匹配跳过,命中即 expose', async t => {
  const exposed: Array<[string, string]> = [];
  const client = { expose: (e: string, v: string) => exposed.push([e, v]) } as any;
  const exps = [
    { id: 'e_all', config: { variants: [{ name: 'A', weight: 1 }, { name: 'B', weight: 1 }] } },
    { id: 'e_ios', config: { variants: [{ name: 'A', weight: 1 }], targeting: { platform: ['ios'] } } },
    { id: 'e_nocfg' },
  ];
  const res = await assignAllAndExpose(client, exps as any, 'k1', 'web', '1.0.0');
  assert.ok(['A', 'B'].includes(res.e_all));
  assert.equal(res.e_ios, undefined);   // 平台不匹配跳过
  assert.equal(res.e_nocfg, 'A');       // 空 variants → A
  assert.equal(exposed.length, 2);
  assert.deepEqual(exposed[1], ['e_nocfg', 'A']);
});

test('assignAllWithTargeting:完整 ctx 过滤', async t => {
  const exposed: Array<[string, string]> = [];
  const client = { expose: (e: string, v: string) => exposed.push([e, v]) } as any;
  const mk = (id: string, targeting: any) => ({ id, salt: 's', config: { variants: [{ name: 'A', weight: 1 }], targeting } });
  const exps = [
    mk('hit_platform', { platform: ['web'] }),
    mk('miss_platform', { platform: ['ios'] }),
    mk('hit_min', { appVersionMin: '1.5.0' }),
    mk('miss_min', { appVersionMin: '2.1.0' }),
    mk('hit_max', { appVersionMax: '2.0.0' }),
    mk('hit_country', { countries: ['US'] }),
  ];
  const res = await assignAllWithTargeting(client, exps as any, 'k', { platform: 'web', appVersion: '2.0.0', country: 'us' });
  assert.deepEqual(Object.keys(res).sort(), ['hit_country', 'hit_max', 'hit_min', 'hit_platform']);
  assert.equal(exposed.length, 4);
});

test('getCachedExperiments:无/坏 JSON/非数组/命中', async t => {
  const env = setup(t);
  assert.equal(getCachedExperiments('g', 'e'), null);
  env.store.set('oddsmaker_experiments_g_e', '{bad');
  assert.equal(getCachedExperiments('g', 'e'), null);
  env.store.set('oddsmaker_experiments_g_e', JSON.stringify({ ts: 1, exps: {} }));
  assert.equal(getCachedExperiments('g', 'e'), null);
  env.store.set('oddsmaker_experiments_g_e', JSON.stringify({ ts: 1, exps: [{ id: 'e1' }] }));
  assert.deepEqual(getCachedExperiments('g', 'e'), [{ id: 'e1' }]);
  // localStorage 抛错 → catch → null
  (globalThis.window as any).localStorage.getItem = () => { throw new Error('denied'); };
  assert.equal(getCachedExperiments('g', 'e'), null);
});

test('fetchExperimentsCached:无缓存 → 拉取并写入缓存', async t => {
  const env = setup(t, [{ status: 200, body: '[{"id":"fresh"}]' }]);
  const r = await fetchExperimentsCached('https://c', 'g2', 'e2');
  assert.deepEqual(r, [{ id: 'fresh' }]);
  const saved = JSON.parse(env.store.get('oddsmaker_experiments_g2_e2')!);
  assert.deepEqual(saved.exps, [{ id: 'fresh' }]);
  assert.ok(saved.ts > 0);
});

test('fetchExperimentsCached:TTL 内命中返回缓存,后台刷新成功更新缓存', async t => {
  const key = 'oddsmaker_experiments_g3_e3';
  const env = setup(t, [{ status: 200, body: '[{"id":"bg"}]' }]);
  env.store.set(key, JSON.stringify({ ts: Date.now(), exps: [{ id: 'cached' }] }));
  const r = await fetchExperimentsCached('https://c', 'g3', 'e3');
  assert.deepEqual(r, [{ id: 'cached' }]);   // 命中缓存立即返回
  await sleep(30);   // 等后台刷新 promise 落盘
  assert.deepEqual(JSON.parse(env.store.get(key)!).exps, [{ id: 'bg' }]);
});

test('fetchExperimentsCached:TTL 内命中,后台刷新失败静默', async t => {
  const key = 'oddsmaker_experiments_g4_e4';
  const env = setup(t, [{ status: 500 }]);
  env.store.set(key, JSON.stringify({ ts: Date.now(), exps: [{ id: 'cached' }] }));
  const r = await fetchExperimentsCached('https://c', 'g4', 'e4');
  assert.deepEqual(r, [{ id: 'cached' }]);
  await sleep(20);
  assert.deepEqual(JSON.parse(env.store.get(key)!).exps, [{ id: 'cached' }]);   // 失败不改缓存
});

test('fetchExperimentsCached:过期/时间戳非数字/结构非法 → 重新拉取', async t => {
  const key = 'oddsmaker_experiments_g5_e5';
  const env = setup(t, [{ status: 200, body: '[{"id":"old-was-expired"}]' }]);
  env.store.set(key, JSON.stringify({ ts: Date.now() - 600_000, exps: [{ id: 'old' }] }));
  const r = await fetchExperimentsCached('https://c', 'g5', 'e5', 300_000);
  assert.deepEqual(r, [{ id: 'old-was-expired' }]);
  assert.deepEqual(JSON.parse(env.store.get(key)!).exps, [{ id: 'old-was-expired' }]);

  env.resetFetch([{ status: 200, body: '[{"id":"ts-not-number"}]' }]);
  env.store.set(key, JSON.stringify({ ts: 'abc', exps: [{ id: 'x' }] }));
  assert.deepEqual(await fetchExperimentsCached('https://c', 'g5', 'e5'), [{ id: 'ts-not-number' }]);

  env.resetFetch([{ status: 200, body: '[{"id":"exps-not-array"}]' }]);
  env.store.set(key, JSON.stringify({ ts: Date.now(), exps: 'nope' }));
  assert.deepEqual(await fetchExperimentsCached('https://c', 'g5', 'e5'), [{ id: 'exps-not-array' }]);
});

test('fetchExperimentsCached:缓存读取抛错 → 兜底直接拉取', async t => {
  const env = setup(t, [{ status: 200, body: '[{"id":"direct"}]' }]);
  (globalThis.window as any).localStorage.getItem = () => { throw new Error('denied'); };
  const r = await fetchExperimentsCached('https://c', 'g6', 'e6');
  assert.deepEqual(r, [{ id: 'direct' }]);
});

test('startExperimentsAutoRefresh:缓存回调容错、tick 成功/失败、interval 触发、stop 停止', async t => {
  const key = 'oddsmaker_experiments_gr_er';
  const env = setup(t, [
    { status: 200, body: '[{"id":"t1"}]' },
    { status: 500 },                     // tick 失败 → catch 静默
    { status: 200, body: '[{"id":"t2"}]' },
  ]);
  env.store.set(key, JSON.stringify({ ts: 1, exps: [{ id: 'cached' }] }));
  const updates: any[][] = [];
  let cachedCalls = 0;
  const stop = startExperimentsAutoRefresh('https://c', 'gr', 'er', exps => {
    if (cachedCalls === 0 && exps[0]?.id === 'cached') { cachedCalls++; throw new Error('consumer boom'); }
    updates.push(exps as any[]);
  }, 25);
  await sleep(120);
  stop();
  const after = env.fetchCalls.length;
  await sleep(60);
  assert.equal(env.fetchCalls.length, after);   // stop 后不再拉取
  assert.deepEqual(updates[0], [{ id: 't1' }]);   // 首个 tick
  assert.ok(updates.some(u => u[0]?.id === 't2'));   // interval 后续 tick
  assert.deepEqual(getCachedExperiments('gr', 'er'), [{ id: 't2' }]);   // 成功 tick 写缓存
});

test('ensureFreshExperimentsAndAssign:拉取 → 过滤 → expose', async t => {
  const env = setup(t, [{ status: 200, body: JSON.stringify([
    { id: 'exp1', salt: 's', config: { variants: [{ name: 'A', weight: 1 }], targeting: { platform: ['web'] } } },
    { id: 'exp2', salt: 's', config: { variants: [{ name: 'A', weight: 1 }], targeting: { platform: ['ios'] } } },
  ]) }]);
  const exposed: Array<[string, string]> = [];
  const res = await ensureFreshExperimentsAndAssign(
    { expose: (e: string, v: string) => exposed.push([e, v]) } as any, 'https://c', 'gf', 'ef', 'user1', { platform: 'web' });
  assert.deepEqual(res, { exp1: 'A' });
  assert.deepEqual(exposed, [['exp1', 'A']]);
});
