# Experiment Platform (A/B) MVP

> 🌐 Language: **English** · 中文 → [experiments.md](experiments.md)

Goal: provide configurable experiments (variants/weights/target rules), consistent assignment and exposure events on the SDK side, and fast aggregation of exposure and conversion metrics on the ClickHouse side.

Data model (control plane)
- Experiment: { id, gameId, environmentId, environment, name, status(draft|live|paused|ended), salt, config }
- There are also the `audienceSegmentId`, `variants`, `allocationInfo`, `guardrails` fields (ExperimentDTO.java:41-44)
- `environmentId`: internal environment ID (either this or `environment`)
- `environment`: logical environment name, e.g. `dev`/`staging`/`prod` (either this or `environmentId`, compatibility field)
- config example:
```json
{
  "variants": [ {"name":"A","weight":50}, {"name":"B","weight":50} ],
  "targeting": { "platform":["android","ios"], "appVersionMin":"1.0.0", "appVersionMax":"2.0.0", "countries":["US","CN"] },
  "metrics": { "primary":"level_complete", "secondary":["revenue"] }
}
```

Control plane API (brief)
- POST /api/experiments {id,gameId,environment,name,status,salt,config}
- GET /api/experiments?gameId=&environment=&status=
- POST /api/experiments/{id}/publish | /pause | DELETE /api/experiments/{id}
- Public config: GET /api/config/{gameId}/{environment} → [{id,salt,config}]

SDK assignment and exposure (pseudocode, TypeScript)
```ts
function assign(exp: {id:string,salt:string,variants:{name:string,weight:number}[]}, key: string): string {
  // key: userId or deviceId; use stable hashing
  const sum = exp.variants.reduce((a,v)=>a+v.weight,0);
  // hash32 = FNV-1a 32 位变体，与服务端 ExperimentSplitter 同款；
  // 锚点：hash32("a")=0xe40c292c、hash32("foobar")=0xbf9cf968
  const h = hash32(exp.id + ':' + (exp.salt||'') + ':' + key) % sum;
  let acc = 0; for (const v of exp.variants) { acc += v.weight; if (h < acc) return v.name; }
  return exp.variants[0].name;
}
// 使用：从 /api/config/{gameId}/{environment} 拉取实验；过滤 targeting；按 userId/设备分配 variant；上报曝光
oddsmaker.track("experiment_exposure", { exp: exp.id, variant });
```

SDK usage examples
- Web (TS)
```ts
import { fetchExperimentsCached, assignAllWithTargeting, startExperimentsAutoRefresh } from './dist/index.js';
// 读取本地缓存（默认 5 分钟 TTL），并后台刷新
const environment = 'prod';
const exps = await fetchExperimentsCached('http://localhost:8085', 'p1', environment);
const ctx = { platform: 'web', appVersion: '1.2.3', country: 'US' };
const assignments = await assignAllWithTargeting(oddsmaker, exps, userId || deviceId, ctx);
// 可选：自动刷新实验配置（默认每 5 分钟），刷新后回调
const stop = startExperimentsAutoRefresh('http://localhost:8085', 'p1', environment, (newExps)=>{
  // 在合适时机重新分配/曝光，或仅用于 UI 控制
});
// 需要时停止：stop();
```
- Android (Kotlin)
```kotlin
val raw = oddsmaker.fetchExperiments("http://10.0.2.2:8085", "p1", "prod") // returns JSON string
// parse and call oddsmaker.assignVariant(expId, salt, listOf(Variant("A",50), Variant("B",50)), userKey)
```
- iOS (Swift)
```swift
Oddsmaker.shared.fetchExperiments(URL(string:"http://localhost:8085")!, gameId:"p1", environment:"prod") { result in
  if case .success(let data) = result { /* decode and assign via Oddsmaker.assignVariant */ }
}
```
- Unity (C#)
```csharp
var variant = Oddsmaker.Oddsmaker.AssignVariant("exp1", "salt", new List<Tuple<string,int>>{ Tuple.Create("A",50), Tuple.Create("B",50) }, userKey);
Oddsmaker.Oddsmaker.Track("experiment_exposure", new Dictionary<string,object>{{"exp","exp1"},{"variant",variant}});
```

Event specification
- Exposure: `experiment_exposure`, props: { exp: `<id>`, variant: `<name>` }
- Conversion events: reported per business, e.g. `level_complete` / `purchase`

ClickHouse aggregation (example)
- Exposure: filter `experiment_exposure` from events and aggregate by day/variant
- Conversion: count conversion events within the window for the set of exposed users (SQL in queries_experiment.sql)
  - Dimension-based views: views with `platform/app_version/country` dimensions are already provided (`v_exp_exposures_by_day_dim`, `v_exp_conversion_24h_dim`, `v_exp_conversion_7d_dim`); you can create the corresponding datasets in Superset to filter or group by dimensions.
  - For large data volumes: import `schema/sql/clickhouse/schema_experiments_mv.sql` and `schema/sql/clickhouse/queries_experiment_mv.sql`, and prefer the MV-based `*_fast` views to reduce scan cost.

Configuration validation
- Recommended reference JSON Schema: `schema/json/experiment-config.schema.json`
- The console and server currently perform basic validation (at least 2 variants, positive integer weights with sum > 0, unique names, type checks for targeting/metrics); JSON Schema validation can be introduced later to improve the quality of validation feedback.

Dashboards (Superset)
- Daily exposure volume (by variant)
- Conversion rate (by variant)
- Uplift (B vs A)
