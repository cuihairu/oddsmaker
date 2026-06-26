export type OddsmakerOptions = {
  apiKey: string;
  endpoint: string;
  gameId: string;
  environment: string;
  deviceId?: string;
  flushIntervalMs?: number;
  maxBatch?: number;
  maxQueueBytes?: number;
  sessionGapMs?: number;
  debug?: boolean;
};

export type EventProps = Record<string, any>;
export type LevelRef = string | number;
export type RevenueProps = EventProps & {
  order_id?: string;
  product_id?: string;
  store?: string;
  placement_id?: string;
};
export type AdImpressionProps = EventProps & {
  ad_unit_id?: string;
  placement_id?: string;
  ad_format?: string;
  network?: string;
  precision?: string;
};

type Event = {
  event_id: string;
  game_id: string;
  environment: string;
  event_type: string;
  event_name: string;
  user_id?: string | null;
  device_id: string;
  player_id?: string | null;
  character_id?: string | null;
  session_id?: string | null;
  ts_client: number;
  platform?: string | null;
  app_version?: string | null;
  country?: string | null;
  server_id?: string | null;
  guild_id?: string | null;
  match_id?: string | null;
  level_id?: string | null;
  game_mode?: string | null;
  difficulty?: string | null;
  progression_path?: string | null;
  order_id?: string | null;
  product_id?: string | null;
  revenue_amount?: number | null;
  revenue_currency?: string | null;
  receipt_hash?: string | null;
  virtual_currency?: string | null;
  virtual_amount?: number | null;
  item_id?: string | null;
  resource_id?: string | null;
  resource_amount?: number | null;
  flow_type?: string | null;
  operation_id?: string | null;
  operation_type?: string | null;
  ad_network?: string | null;
  ad_placement?: string | null;
  ad_format?: string | null;
  ad_impression_id?: string | null;
  risk_context?: string | null;
  device_fingerprint?: string | null;
  client_integrity?: string | null;
  experiments?: Record<string, string> | null;
  props?: EventProps | null;
};

function nowMs() { return Date.now(); }

function uuidv7(): string {
  const t = BigInt(nowMs());
  const tsHex = t.toString(16).padStart(12, '0').slice(-12);
  const r1 = crypto.getRandomValues(new Uint32Array(2));
  const rndHex = r1[0].toString(16).padStart(8, '0') + r1[1].toString(16).padStart(8, '0');
  const v7RndHex = '7' + rndHex.slice(1);
  const r2 = crypto.getRandomValues(new Uint32Array(2));
  let variantByte = (r2[0] >>> 24) & 0xff;
  variantByte = (variantByte & 0x3f) | 0x80;
  const tailHex = variantByte.toString(16).padStart(2, '0') + r2[0].toString(16).padStart(8, '0').slice(2) + r2[1].toString(16).padStart(8, '0');
  const hex = tsHex + v7RndHex + tailHex;
  return (
    hex.slice(0, 8) + '-' +
    hex.slice(8, 12) + '-' +
    hex.slice(12, 16) + '-' +
    hex.slice(16, 20) + '-' +
    hex.slice(20, 32)
  );
}

function storageGet(key: string): string | null {
  try { return window.localStorage.getItem(key); } catch { return null; }
}
function storageSet(key: string, val: string) {
  try { window.localStorage.setItem(key, val); } catch {}
}

class Queue {
  private items: Event[] = [];
  private bytes = 0;
  constructor(private maxQueueBytes: number) {}
  push(e: Event) {
    const est = JSON.stringify(e).length + 1;
    this.items.push(e);
    this.bytes += est;
  }
  drain(max: number): Event[] {
    const out = this.items.splice(0, Math.min(max, this.items.length));
    this.bytes = this.items.reduce((acc, it) => acc + JSON.stringify(it).length + 1, 0);
    return out;
  }
  size() { return this.items.length; }
  overLimit() { return this.bytes >= this.maxQueueBytes; }
  snapshot() { return this.items.slice(); }
  restore(items: Event[]) {
    this.items = items;
    this.bytes = items.reduce((acc, it) => acc + JSON.stringify(it).length + 1, 0);
  }
}

export class Oddsmaker {
  private apiKey: string;
  private endpoint: string;
  private gameId: string;
  private environment: string;
  private deviceId: string;
  private userId: string | null = null;
  private playerId: string | null = null;
  private userProps: Record<string, string | number | boolean | null> = {};
  private queue: Queue;
  private flushInterval: number;
  private maxBatch: number;
  private sessionGapMs: number;
  private timer: any = null;
  private sessionId: string | null = null;
  private lastActive = 0;
  private debug = false;

  constructor(opts: OddsmakerOptions) {
    this.apiKey = opts.apiKey;
    this.endpoint = opts.endpoint.replace(/\/$/, '');
    this.gameId = opts.gameId;
    this.environment = opts.environment;
    this.flushInterval = opts.flushIntervalMs ?? 5000;
    this.maxBatch = opts.maxBatch ?? 50;
    this.queue = new Queue(opts.maxQueueBytes ?? 512_000);
    this.sessionGapMs = opts.sessionGapMs ?? 30 * 60 * 1000;
    this.debug = !!opts.debug;

    const k = `oddsmaker_device_id_${this.gameId}_${this.environment}`;
    this.deviceId = opts.deviceId || storageGet(k) || this.randomDeviceId();
    storageSet(k, this.deviceId);

    const saved = storageGet(this.queueKey());
    if (saved) {
      try { this.queue.restore(JSON.parse(saved)); } catch {}
    }

    this.lastActive = nowMs();
    this.ensureTimer();
    if (typeof window !== 'undefined') {
      window.addEventListener('online', () => this.flush());
      window.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'hidden') this.flush();
      });
    }
  }

  setUserId(userId: string | null) { this.userId = userId; }
  setUserProps(props: Record<string, string | number | boolean | null>) { this.userProps = { ...this.userProps, ...props }; }
  setPlayer(playerId: string | null) { this.playerId = playerId; }

  identify(userId: string, props?: EventProps): string {
    const previousUserId = this.userId;
    this.userId = userId;
    const identifyProps: EventProps = { $identify: true, new_user_id: userId };
    if (previousUserId && previousUserId !== userId) identifyProps.previous_user_id = previousUserId;
    if (this.playerId) identifyProps.player_id = this.playerId;
    return this.track('$identify', { ...identifyProps, ...(props || {}) });
  }

  track(eventName: string, props?: EventProps): string {
    return this.queueEvent(eventName, props);
  }

  private queueEvent(eventName: string, props?: EventProps, extra?: Partial<Event>): string {
    const ts = nowMs();
    this.rollSession(ts);
    const evt: Event = {
      event_id: uuidv7(),
      game_id: this.gameId,
      environment: this.environment,
      event_type: inferEventType(eventName),
      event_name: eventName,
      user_id: this.userId ?? undefined,
      device_id: this.deviceId,
      player_id: this.playerId ?? undefined,
      session_id: this.sessionId ?? undefined,
      ts_client: ts,
      platform: 'web',
      props: this.mergeProps(props),
      ...(extra || {})
    };
    return this.enqueueEvent(evt, ts);
  }

  private enqueueEvent(evt: Event, ts: number): string {
    this.queue.push(evt);
    this.lastActive = ts;
    if (this.debug) console.debug('[oddsmaker] queued', evt.event_id, evt.event_name);
    this.persistQueue();
    if (this.queue.overLimit() || this.queue.size() >= this.maxBatch) this.flush();
    return evt.event_id;
  }

  expose(exp: string, variant: string) {
    return this.track('experiment_exposure', { exp, variant });
  }

  revenue(amount: number, currency: string, props?: EventProps) {
    return this.queueRevenueEvent('revenue', amount, currency, props);
  }

  tutorialStart(tutorialId: string, props?: EventProps) {
    return this.track('tutorial_start', this.withCoreProps(props, { tutorial_id: tutorialId }));
  }

  tutorialComplete(tutorialId: string, props?: EventProps) {
    return this.track('tutorial_complete', this.withCoreProps(props, { tutorial_id: tutorialId }));
  }

  levelStart(levelId: LevelRef, props?: EventProps) {
    const levelIdText = String(levelId);
    const merged = this.withCoreProps(props, { level_id: levelIdText });
    return this.queueEvent('level_start', merged, {
      level_id: levelIdText,
      game_mode: stringProp(merged, 'game_mode')
    });
  }

  levelFail(levelId: LevelRef, reason: string, props?: EventProps) {
    const levelIdText = String(levelId);
    const merged = this.withCoreProps(props, { level_id: levelIdText, fail_reason: reason });
    return this.queueEvent('level_fail', merged, {
      level_id: levelIdText,
      game_mode: stringProp(merged, 'game_mode')
    });
  }

  levelComplete(levelId: LevelRef, props?: EventProps) {
    const levelIdText = String(levelId);
    const merged = this.withCoreProps(props, { level_id: levelIdText });
    return this.queueEvent('level_complete', merged, {
      level_id: levelIdText,
      game_mode: stringProp(merged, 'game_mode')
    });
  }

  currencySource(currency: string, amount: number, props?: EventProps) {
    const currencyCode = currency.toUpperCase();
    return this.queueEvent('currency_source', this.withCoreProps(props, {
      currency_code: currencyCode,
      amount
    }), {
      resource_id: currencyCode,
      resource_amount: amount,
      virtual_currency: currencyCode,
      virtual_amount: amount,
      flow_type: 'source'
    });
  }

  currencySink(currency: string, amount: number, props?: EventProps) {
    const currencyCode = currency.toUpperCase();
    return this.queueEvent('currency_sink', this.withCoreProps(props, {
      currency_code: currencyCode,
      amount
    }), {
      resource_id: currencyCode,
      resource_amount: amount,
      virtual_currency: currencyCode,
      virtual_amount: amount,
      flow_type: 'sink'
    });
  }

  itemGrant(itemId: string, quantity = 1, props?: EventProps) {
    return this.queueEvent('item_grant', this.withCoreProps(props, { item_id: itemId, quantity }), {
      item_id: itemId,
      resource_id: itemId,
      resource_amount: quantity,
      flow_type: 'source'
    });
  }

  itemConsume(itemId: string, quantity = 1, props?: EventProps) {
    return this.queueEvent('item_consume', this.withCoreProps(props, { item_id: itemId, quantity }), {
      item_id: itemId,
      resource_id: itemId,
      resource_amount: quantity,
      flow_type: 'sink'
    });
  }

  iapOrder(orderId: string, amount: number, currency: string, props?: RevenueProps) {
    const merged = this.withCoreProps(props, { order_id: orderId });
    return this.queueRevenueEvent('iap_order', amount, currency, merged, {
      order_id: orderId,
      product_id: stringProp(merged, 'product_id')
    });
  }

  webshopOrder(orderId: string, amount: number, currency: string, props?: RevenueProps) {
    const merged = this.withCoreProps(props, { order_id: orderId });
    return this.queueRevenueEvent('webshop_order', amount, currency, merged, {
      order_id: orderId,
      product_id: stringProp(merged, 'product_id')
    });
  }

  adImpression(amount: number, currency: string, props?: AdImpressionProps) {
    return this.queueRevenueEvent('ad_impression', amount, currency, props, {
      ad_network: stringProp(props, 'network'),
      ad_placement: stringProp(props, 'placement_id'),
      ad_format: stringProp(props, 'ad_format')
    });
  }

  rewardedAdComplete(network: string, adUnitId: string, props?: EventProps) {
    return this.track('rewarded_ad_complete', this.withCoreProps(props, { network, ad_unit_id: adUnitId }));
  }

  eventEntry(liveopsEventId: string, props?: EventProps) {
    return this.track('event_entry', this.withCoreProps(props, { liveops_event_id: liveopsEventId }));
  }

  eventRewardClaim(liveopsEventId: string, rewardId: string, props?: EventProps) {
    return this.track('event_reward_claim', this.withCoreProps(props, {
      liveops_event_id: liveopsEventId,
      reward_id: rewardId
    }));
  }

  guildJoin(guildId: string, props?: EventProps) {
    return this.queueEvent('guild_join', this.withCoreProps(props, { guild_id: guildId }), { guild_id: guildId });
  }

  inviteSent(channel: string, props?: EventProps) {
    return this.track('invite_sent', this.withCoreProps(props, { channel }));
  }

  crash(errorName: string, props?: EventProps) {
    return this.track('crash', this.withCoreProps(props, { error_name: errorName }));
  }

  fpsDrop(fps: number, props?: EventProps) {
    return this.track('fps_drop', this.withCoreProps(props, { fps }));
  }

  networkTimeout(endpoint: string, props?: EventProps) {
    return this.track('network_timeout', this.withCoreProps(props, { endpoint }));
  }

  cheatFlag(ruleId: string, riskLevel: string, props?: EventProps) {
    return this.track('cheat_flag', this.withCoreProps(props, { rule_id: ruleId, risk_level: riskLevel }));
  }

  async flush(): Promise<void> {
    if (this.queue.size() === 0) return;
    const batch = this.queue.drain(this.maxBatch);
    await this.send(batch);
    this.persistQueue();
  }

  shutdown() {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }

  private ensureTimer() {
    if (this.timer) return;
    this.timer = setInterval(() => { this.flush().catch(() => {}); }, this.flushInterval);
  }

  private rollSession(ts: number) {
    if (!this.sessionId || ts - this.lastActive > this.sessionGapMs) {
      this.sessionId = uuidv7();
    }
  }

  private mergeProps(p?: EventProps) {
    const base: EventProps = { ...this.userProps };
    if (this.playerId) base.player_id = this.playerId;
    return { ...base, ...(p || {}) };
  }

  private queueRevenueEvent(eventName: string, amount: number, currency: string, props?: EventProps, extra?: Partial<Event>) {
    const normalizedCurrency = currency.toUpperCase();
    return this.queueEvent(
      eventName,
      this.withCoreProps(props, { amount, currency: normalizedCurrency }),
      {
        revenue_amount: amount,
        revenue_currency: normalizedCurrency,
        ...(extra || {})
      }
    );
  }

  private withCoreProps(props: EventProps | undefined, core: EventProps) {
    return { ...(props || {}), ...core };
  }

  private queueKey() { return `oddsmaker_queue_${this.gameId}_${this.environment}_${this.deviceId}`; }
  private persistQueue() { storageSet(this.queueKey(), JSON.stringify(this.queue.snapshot())); }
  private randomDeviceId() { return 'd_' + Math.random().toString(36).slice(2) + Math.random().toString(36).slice(2); }

  private async send(evts: Event[]) {
    const ndjson = evts.map(e => JSON.stringify(e)).join('\n');
    const url = `${this.endpoint}/v1/batch`;
    const headers: Record<string, string> = {
      'x-api-key': this.apiKey,
      'content-type': 'application/x-ndjson'
    };

    let body: BodyInit = ndjson;
    let useGzip = false;
    try {
      if (typeof CompressionStream !== 'undefined') {
        useGzip = true;
        const cs = new CompressionStream('gzip');
        const blob = new Blob([ndjson]);
        const stream = blob.stream().pipeThrough(cs);
        body = await new Response(stream).arrayBuffer();
        headers['content-encoding'] = 'gzip';
      }
    } catch {}

    let backoff = 1000;
    for (let attempt = 1; attempt <= 5; attempt++) {
      try {
        const res = await fetch(url, { method: 'POST', headers, body });
        if (res.ok) {
          if (this.debug) console.debug('[oddsmaker] flushed', evts.length, 'gzip=', useGzip);
          return;
        }
        if (res.status === 429) {
          const retryAfter = Number(res.headers.get('retry-after') || '1');
          await sleep(retryAfter * 1000);
        } else {
          throw new Error(`HTTP ${res.status}`);
        }
      } catch (e) {
        if (attempt === 5) {
          this.queue.restore([...evts, ...this.queue.snapshot()]);
          this.persistQueue();
          if (this.debug) console.warn('[oddsmaker] flush failed, stored offline', e);
          return;
        }
        await sleep(backoff + jitter(250));
        backoff = Math.min(backoff * 2, 30_000);
      }
    }
  }
}

function sleep(ms: number) { return new Promise(r => setTimeout(r, ms)); }
function jitter(n: number) { return Math.floor(Math.random() * n); }
function stringProp(props: EventProps | undefined, key: string): string | undefined {
  const value = props?.[key];
  return typeof value === 'string' ? value : undefined;
}
function inferEventType(eventName: string) {
  const name = eventName.toLowerCase();
  if (name === '$identify' || name.includes('identity')) return 'identity';
  if (name.includes('risk') || name.includes('fraud')) return 'risk';
  if (name.includes('experiment')) return 'experiment';
  if (name.includes('ad_')) return 'ad';
  if (name.includes('level') || name.includes('quest')) return 'progression';
  if (name.includes('session')) return 'session';
  if (name.includes('error') || name.includes('crash')) return 'error';
  return 'business';
}

export type Variant = { name: string; weight: number };
export type ExperimentCfg = { id: string; salt?: string; config?: { variants?: Variant[]; targeting?: any } };

export function hash32(s: string): number {
  let h = 0x811c9dc5;
  for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h += (h << 1) + (h << 4) + (h << 7) + (h << 8) + (h << 24); }
  return (h >>> 0);
}

export function assignVariant(exp: { id: string; salt?: string; variants: Variant[] }, key: string): string {
  const vars = exp.variants || [];
  if (!vars.length) return 'A';
  const sum = vars.reduce((a, v) => a + (v.weight || 0), 0) || vars.length;
  const h = hash32(exp.id + ':' + (exp.salt || '') + ':' + key) % sum;
  let acc = 0;
  for (const v of vars) {
    acc += (v.weight || 0) || 1;
    if (h < acc) return v.name;
  }
  return vars[0].name;
}

export async function fetchExperiments(controlEndpoint: string, gameId: string, environment: string): Promise<ExperimentCfg[]> {
  const url = controlEndpoint.replace(/\/$/, '') + `/api/config/${encodeURIComponent(gameId)}/${encodeURIComponent(environment)}`;
  const r = await fetch(url, { headers: { accept: 'application/json' } });
  if (!r.ok) throw new Error('fetch experiments failed: ' + r.status);
  return r.json();
}

export async function assignAllAndExpose(client: Oddsmaker, exps: ExperimentCfg[], userKey: string, platform?: string, appVersion?: string): Promise<Record<string, string>> {
  const res: Record<string, string> = {};
  const ctx: ExpContext = { platform, appVersion };
  for (const e of exps) {
    const cfg = e.config || {} as any;
    const t = (cfg.targeting as Targeting) || undefined;
    if (!matchTargeting(t, ctx)) continue;
    const vars = (cfg.variants as Variant[]) || [];
    const v = assignVariant({ id: e.id, salt: e.salt, variants: vars }, userKey);
    res[e.id] = v;
    client.expose(e.id, v);
  }
  return res;
}

export type Targeting = { platform?: string[]; appVersionMin?: string; appVersionMax?: string; countries?: string[] };
export type ExpContext = { platform?: string; appVersion?: string; country?: string };

export function versionGte(a: string, b: string): boolean {
  const pa = a.split('.').map(x => parseInt(x, 10) || 0);
  const pb = b.split('.').map(x => parseInt(x, 10) || 0);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const ai = pa[i] || 0;
    const bi = pb[i] || 0;
    if (ai > bi) return true;
    if (ai < bi) return false;
  }
  return true;
}
export function versionLte(a: string, b: string): boolean { return versionGte(b, a); }

export function matchTargeting(t: Targeting | undefined, ctx: ExpContext): boolean {
  if (!t) return true;
  if (t.platform && t.platform.length && ctx.platform && !t.platform.includes(ctx.platform)) return false;
  if (t.appVersionMin && ctx.appVersion && !versionGte(ctx.appVersion, t.appVersionMin)) return false;
  if (t.appVersionMax && ctx.appVersion && !versionLte(ctx.appVersion, t.appVersionMax)) return false;
  if (t.countries && t.countries.length && ctx.country) {
    const c = ctx.country.toUpperCase();
    if (!t.countries.map(x => x.toUpperCase()).includes(c)) return false;
  }
  return true;
}

export async function assignAllWithTargeting(client: Oddsmaker, exps: ExperimentCfg[], userKey: string, ctx: ExpContext): Promise<Record<string, string>> {
  const res: Record<string, string> = {};
  for (const e of exps) {
    const cfg = e.config || {} as any;
    const t = (cfg.targeting as Targeting) || undefined;
    if (!matchTargeting(t, ctx)) continue;
    const vars = (cfg.variants as Variant[]) || [];
    const v = assignVariant({ id: e.id, salt: e.salt, variants: vars }, userKey);
    res[e.id] = v;
    client.expose(e.id, v);
  }
  return res;
}

type ExperimentsCacheEntry = { ts: number; exps: ExperimentCfg[] };
function expsCacheKey(gameId: string, environment: string) { return `oddsmaker_experiments_${gameId}_${environment}`; }

export function getCachedExperiments(gameId: string, environment: string): ExperimentCfg[] | null {
  const raw = storageGet(expsCacheKey(gameId, environment));
  if (!raw) return null;
  try {
    const o = JSON.parse(raw) as ExperimentsCacheEntry;
    return Array.isArray(o.exps) ? o.exps : null;
  } catch {
    return null;
  }
}

export async function fetchExperimentsCached(controlEndpoint: string, gameId: string, environment: string, ttlMs = 300_000): Promise<ExperimentCfg[]> {
  const now = nowMs();
  try {
    const raw = storageGet(expsCacheKey(gameId, environment));
    if (raw) {
      const o = JSON.parse(raw) as ExperimentsCacheEntry;
      if (o && Array.isArray(o.exps) && typeof o.ts === 'number' && (now - o.ts) < ttlMs) {
        void fetchExperiments(controlEndpoint, gameId, environment).then(exps => {
          storageSet(expsCacheKey(gameId, environment), JSON.stringify({ ts: nowMs(), exps }));
        }).catch(() => {});
        return o.exps;
      }
    }
  } catch {}
  const exps = await fetchExperiments(controlEndpoint, gameId, environment);
  storageSet(expsCacheKey(gameId, environment), JSON.stringify({ ts: nowMs(), exps }));
  return exps;
}

export function startExperimentsAutoRefresh(controlEndpoint: string, gameId: string, environment: string, onUpdate: (exps: ExperimentCfg[]) => void, intervalMs = 300_000): () => void {
  let stopped = false;
  const cached = getCachedExperiments(gameId, environment);
  if (cached) {
    try { onUpdate(cached); } catch {}
  }
  const tick = async () => {
    try {
      const exps = await fetchExperiments(controlEndpoint, gameId, environment);
      storageSet(expsCacheKey(gameId, environment), JSON.stringify({ ts: nowMs(), exps }));
      if (!stopped) onUpdate(exps);
    } catch {}
  };
  void tick();
  const h = setInterval(() => { void tick(); }, intervalMs);
  return () => { stopped = true; clearInterval(h); };
}

export async function ensureFreshExperimentsAndAssign(client: Oddsmaker, controlEndpoint: string, gameId: string, environment: string, userKey: string, ctx: ExpContext, ttlMs = 300_000): Promise<Record<string, string>> {
  const exps = await fetchExperimentsCached(controlEndpoint, gameId, environment, ttlMs);
  return assignAllWithTargeting(client, exps, userKey, ctx);
}
