// Simple hash and assignment consistency checks for Web SDK.
// Usage: npm run build && npm run test:hash

import { Oddsmaker, hash32, assignVariant, versionGte, versionLte } from '../dist/index.js';

function assert(cond, msg){ if(!cond) { console.error('FAIL:', msg); process.exitCode=1; } }

// FNV-1a 32 test vectors (subset, strings)
const vectors = [
  ['', 0x811c9dc5 >>> 0],
  ['a', 0xe40c292c >>> 0],
  ['foobar', 0xbf9cf968 >>> 0],
  ['Oddsmaker', hash32('Oddsmaker')], // self-check
];

for (const [s, expected] of vectors) {
  const h = hash32(s) >>> 0; // ensure unsigned
  if (typeof expected === 'number') assert(h === expected, `hash32('${s}')=${h} != ${expected}`);
}

// Assignment determinism
const exp = { id: 'exp_consistency', salt: 's', variants: [{name:'A',weight:50},{name:'B',weight:50}] };
const keys = ['u1','u2','u3','device123','device456'];
const res = keys.map(k => assignVariant(exp, k));
console.log('assignments', res);
assert(res.length === 5, 'assign length');
// Re-run to ensure determinism
const res2 = keys.map(k => assignVariant(exp, k));
assert(JSON.stringify(res) === JSON.stringify(res2), 'deterministic assignment');

// Version comparison
assert(versionGte('1.2.3','1.2.0') && versionLte('1.2.3','1.2.3'), 'version compare basic');
assert(!versionGte('1.2.0','1.3.0'), 'versionGte negative');

async function assertRevenueAutoFlushPayload() {
  const oldWindow = globalThis.window;
  const oldFetch = globalThis.fetch;
  const hadCompressionStream = 'CompressionStream' in globalThis;
  const oldCompressionStream = globalThis.CompressionStream;
  const store = new Map();
  let sentBody = null;

  globalThis.window = {
    localStorage: {
      getItem: key => store.has(key) ? store.get(key) : null,
      setItem: (key, value) => { store.set(key, String(value)); },
    },
    addEventListener: () => {},
  };
  globalThis.CompressionStream = undefined;
  globalThis.fetch = async (_url, init) => {
    sentBody = init.body;
    return { ok: true, status: 204, headers: { get: () => null } };
  };

  try {
    const client = new Oddsmaker({
      apiKey: 'pk_test',
      endpoint: 'http://localhost:8080',
      gameId: 'game_demo',
      environment: 'prod',
      deviceId: 'd_test',
      maxBatch: 1,
      flushIntervalMs: 60_000,
    });
    client.setUserProps({ channel: 'organic' });
    const eventId = client.revenue(9.99, 'USD', { sku: 'noads' });
    for (let i = 0; i < 10 && sentBody == null; i++) await Promise.resolve();
    client.shutdown();

    assert(typeof sentBody === 'string', 'revenue auto flush body captured');
    const evt = JSON.parse(sentBody);
    assert(evt.event_id === eventId, 'revenue event id preserved');
    assert(evt.revenue_amount === 9.99, 'revenue_amount present before auto flush');
    assert(evt.revenue_currency === 'USD', 'revenue_currency present before auto flush');
    assert(evt.props.channel === 'organic', 'user props merged into revenue event');
    assert(evt.props.sku === 'noads', 'event props merged into revenue event');
    assert(evt.props.amount === 9.99 && evt.props.currency === 'USD', 'amount/currency mirrored in props');
  } finally {
    if (oldWindow === undefined) delete globalThis.window;
    else globalThis.window = oldWindow;
    globalThis.fetch = oldFetch;
    if (hadCompressionStream) globalThis.CompressionStream = oldCompressionStream;
    else delete globalThis.CompressionStream;
  }
}

await assertRevenueAutoFlushPayload();

console.log('hash_test: OK');
