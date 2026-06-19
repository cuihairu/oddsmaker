import http from 'k6/http';
import { check } from 'k6';

/*****
ENV:
- ENDPOINT: e.g. http://localhost:8080
- API_KEY: e.g. pk_test_example
- GAME_ID: e.g. p1
- ENVIRONMENT: e.g. dev
- RATE: iterations per second (default 100)
- DURATION: test duration like 60s
- BATCH: events per request (default 50)
*****/

export const options = {
  scenarios: {
    constant: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 100), // iters/s
      timeUnit: '1s',
      duration: __ENV.DURATION || '60s',
      preAllocatedVUs: 50,
      maxVUs: 500,
    },
  },
};

const endpoint = (__ENV.ENDPOINT || 'http://localhost:8080').replace(/\/$/, '');
const apiKey = __ENV.API_KEY || 'pk_test_example';
const gameId = __ENV.GAME_ID || 'p1';
const environment = __ENV.ENVIRONMENT || 'dev';
const batch = Number(__ENV.BATCH || 50);

function uuidv7() {
  const ms = Date.now().toString(16).padStart(12, '0');
  const rand = Math.random().toString(16).slice(2).padEnd(20, '0');
  const hex = ms + rand;
  return (
    hex.slice(0,8)+'-'+hex.slice(8,12)+'-7'+hex.slice(13,16)+'-'+hex.slice(16,20)+'-'+hex.slice(20,32)
  );
}

function line(ts) {
  return JSON.stringify({
    event_id: uuidv7(),
    event_type: 'progression',
    event_name: 'level_start',
    game_id: gameId,
    environment: environment,
    device_id: 'd_'+Math.random().toString(36).slice(2),
    ts_client: ts,
    props: { level: Math.floor(Math.random()*100) }
  });
}

export default function () {
  const ts = Date.now();
  let body = '';
  for (let i=0; i<batch; i++) {
    if (i>0) body += '\n';
    body += line(ts + i);
  }
  const res = http.post(`${endpoint}/v1/batch`, body, {
    headers: {
      'x-api-key': apiKey,
      'content-type': 'application/x-ndjson',
    },
    timeout: '30s',
    tags: { name: 'batch' }
  });
  check(res, {
    'status is 200/207': (r) => r.status === 200 || r.status === 207,
  });
}
