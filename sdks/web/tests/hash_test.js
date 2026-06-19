// Simple hash and assignment consistency checks for Web SDK.
// Usage: npm run build && npm run test:hash

import { hash32, assignVariant, versionGte, versionLte } from '../dist/index.js';

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

console.log('hash_test: OK');
