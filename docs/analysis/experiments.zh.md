# 实验平台（A/B）MVP

目标：提供可配置的实验（variants/权重/目标规则），SDK 侧一致性分流与曝光事件，ClickHouse 侧快速聚合曝光/转化指标。

数据模型（控制面）
- Experiment: { id, projectId, name, status(draft|running|paused), salt, config }
- config 示例：
```json
{
  "variants": [ {"name":"A","weight":50}, {"name":"B","weight":50} ],
  "targeting": { "platform":["android","ios"], "appVersionMin":"1.0.0", "appVersionMax":"2.0.0", "countries":["US","CN"] },
  "metrics": { "primary":"level_complete", "secondary":["revenue"] }
}
```

控制面 API（简要）
- POST /api/experiments {id,projectId,name,status,salt,config}
- GET /api/experiments?projectId=&status=
- POST /api/experiments/{id}/publish | /pause | DELETE /api/experiments/{id}
- Public 配置：GET /api/config/{projectId} → [{id,salt,config}]

SDK 分流与曝光（伪代码，TypeScript）
```ts
function assign(exp: {id:string,salt:string,variants:{name:string,weight:number}[]}, key: string): string {
  // key: userId or deviceId; use stable hashing
  const sum = exp.variants.reduce((a,v)=>a+v.weight,0);
  const h = murmur3(exp.id + ':' + (exp.salt||'') + ':' + key) % sum;
  let acc = 0; for (const v of exp.variants) { acc += v.weight; if (h < acc) return v.name; }
  return exp.variants[0].name;
}
// 使用：从 /api/config/{projectId} 拉取实验；过滤 targeting；按 userId/设备分配 variant；上报曝光
pit.expose(exp.id, variant);
```

SDK 使用示例
- Web (TS)
```ts
import { fetchExperimentsCached, assignAllWithTargeting, startExperimentsAutoRefresh } from './dist/index.js';
// 读取本地缓存（默认 5 分钟 TTL），并后台刷新
const exps = await fetchExperimentsCached('http://localhost:8085', 'p1');
const ctx = { platform: 'web', appVersion: '1.2.3', country: 'US' };
const assignments = await assignAllWithTargeting(pt, exps, userId || deviceId, ctx);
// 可选：自动刷新实验配置（默认每 5 分钟），刷新后回调
const stop = startExperimentsAutoRefresh('http://localhost:8085', 'p1', (newExps)=>{
  // 在合适时机重新分配/曝光，或仅用于 UI 控制
});
// 需要时停止：stop();
```
- Android (Kotlin)
```kotlin
val raw = pt.fetchExperiments("http://10.0.2.2:8085", "p1") // returns JSON string
// parse and call pt.assignVariant(expId, salt, listOf(Variant("A",50), Variant("B",50)), userKey)
```
- iOS (Swift)
```swift
Pit.shared.fetchExperiments(URL(string:"http://localhost:8085")!, projectId:"p1") { result in
  if case .success(let data) = result { /* decode and assign via Pit.assignVariant */ }
}
```
- Unity (C#)
```csharp
var variant = Pit.Pit.AssignVariant("exp1", "salt", new List<Tuple<string,int>>{ Tuple.Create("A",50), Tuple.Create("B",50) }, userKey);
Pit.Pit.Track("experiment_exposure", new Dictionary<string,object>{{"exp","exp1"},{"variant",variant}});
```

事件规范
- 曝光：`experiment_exposure`，props: { exp: <id>, variant: <name> }
- 转化事件：按业务上报，如 `level_complete` / `purchase`

ClickHouse 聚合（示例）
- 曝光：从 events 过滤 `experiment_exposure`，聚合到按日/variant
- 转化：按曝光用户集合在窗口内统计转化事件（SQL 在 queries_experiment.sql）
 - 维度版视图：已提供包含 `platform/app_version/country` 维度的视图（`v_exp_exposures_by_day_dim`, `v_exp_conversion_24h_dim`, `v_exp_conversion_7d_dim`），可在 Superset 中建对应数据集进行按维度筛选或分组。
 - 大数据量建议：导入 `schema/sql/clickhouse/schema_experiments_mv.sql` 与 `schema/sql/clickhouse/queries_experiment_mv.sql`，优先查询基于 MV 的 `*_fast` 视图以降低扫描成本。

配置校验
- 推荐参考 JSON Schema：`schema/json/experiment-config.schema.json`
- 当前控制台与服务端已做基础校验（variants 至少 2 个、权重非负且和>0、名称唯一、targeting/metrics 类型检查）；后续可引入 JSON Schema 校验以提升提示质量。

仪表（Superset）
- 每日曝光量（按 variant）
- 转化率（按 variant）
- Uplift（B vs A）
