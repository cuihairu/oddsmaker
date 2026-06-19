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

type Event = {
  event_id: string;
  game_id: string;
  environment: string;
  event_type: string;
  event_name: string;
  user_id?: string | null;
  device_id: string;
  session_id?: string | null;
  ts_client: number;
  platform?: string | null;
  app_version?: string | null;
  country?: string | null;
  revenue_amount?: number | null;
  revenue_currency?: string | null;
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

  track(eventName: string, props?: EventProps): string {
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
      session_id: this.sessionId ?? undefined,
      ts_client: ts,
      platform: 'web',
      props: this.mergeProps(props)
    };
    this.queue.push(evt);
    this.lastActive = ts;
    if (this.debug) console.debug('[oddsmaker] queued', evt.event_id, eventName);
    if (this.queue.overLimit()) this.flush();
    return evt.event_id;
  }

  expose(exp: string, variant: string) {
    return this.track('experiment_exposure', { exp, variant });
  }

  revenue(amount: number, currency: string, props?: EventProps) {
    const eventId = this.track('revenue', { ...(props || {}) });
    const snapshot = this.queue.snapshot();
    const last = snapshot[snapshot.length - 1];
    if (last && last.event_id === eventId) {
      last.revenue_amount = amount;
      last.revenue_currency = currency;
      last.props = this.mergeProps({ amount, currency, ...(props || {}) });
    }
    return eventId;
  }

  async flush(): Promise<void> {
    if (this.queue.size() === 0) return;
    const batch = this.queue.drain(this.maxBatch);
    await this.send(batch);
    storageSet(this.queueKey(), JSON.stringify(this.queue.snapshot()));
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
    return { ...this.userProps, ...(p || {}) };
  }

  private queueKey() { return `oddsmaker_queue_${this.gameId}_${this.environment}_${this.deviceId}`; }
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
          storageSet(this.queueKey(), JSON.stringify(this.queue.snapshot()));
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
function inferEventType(eventName: string) {
  const name = eventName.toLowerCase();
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
