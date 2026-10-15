# MMP Attribution Integration Evaluation (P7-5 Research Report)

> 🌐 Language: **English** · 中文 → [mmp-attribution-evaluation.md](mmp-attribution-evaluation.md)

> Corresponds to item 5 "Attribution / UA (MMP integration, CPI/ROAS)" in the gap matrix of `docs/competitive-analysis.md`.
> Conclusion first: **Technically feasible, path is clear, but it depends heavily on an external MMP account and the right plan tier — recommend initiating the project (P8) with actual account availability as a precondition**. This document provides the table-creation draft and the recommended integration path.

## 1. Background

The competitive analysis (2026-09) ranked MMP attribution as the 5th gap: value 3/5 (5/5 for UA-type studios), cost 4/5, annotated as "research first, then decide whether to initiate." Current state of this repository:

- **Already have**: the `attribution Map(String,String)` fallback field on the `events` table, the four fallback dimension columns `ad_network / ad_placement / ad_format / ad_impression_id`, and online reporting that aggregates channels by falling back from `attribution.channel` to `platform`.
- **Missing**: there is no ingestion channel for raw MMP-side attribution data (install attribution, attributed in-app events, ad spend), so we cannot answer "which channels brought the users, how much was spent, how much was recovered." The current `attribution` field only carries the channel tags that the game server tags itself, which is unrelated to MMP attribution decisions (last-click / impression matching).

## 2. Research on Mainstream MMP Data Sources

We investigated the two leaders AppsFlyer and Adjust, plus the newcomer Airbridge (Branch/Singular follow the same pattern as Adjust and are not expanded here).

### 2.1 AppsFlyer

> **Conclusion:** all four channels are available — Push API for real-time push + Data Locker for the most complete batch delivery (higher-tier plan), Pull API suitable for backfill, and postbacks for reconciliation.

| Mechanism | Form | Latency | Content | Notes |
|---|---|---|---|---|
| **Push API** | Real-time streaming push to your HTTPS endpoint | Second-level | Raw install + in-app event attribution data (including SKAN) | Requires building your own receiving endpoint; handle signing / retry / idempotency |
| **Data Locker** | Scheduled writes to **your own** S3/GCS bucket | Hourly / daily subdirectories | CSV datasets: installs, in-app events, uninstalls, SKAN, etc. (30+ datasets) | The batch channel with the most complete raw data; a higher-tier plan feature |
| **Raw Data Pull API V2** | Pull-style query API | On demand | Raw install / event detail records (token-authenticated) | Suitable for backfill, not for the main pipeline |
| **Postbacks by date API** | Pull-style | On demand | Attribution postback details by date | For reconciliation |

### 2.2 Adjust

> **Conclusion:** structurally isomorphic to AppsFlyer — real-time callbacks + scheduled CSV uploads in two forms; S2S is the reverse channel, indicating the same account can go bidirectionally.

| Mechanism | Form | Latency | Content | Notes |
|---|---|---|---|---|
| **Real-time callbacks** (global callbacks) | Callbacks to your HTTPS endpoint when an event occurs | Real-time | Install, session, and event attribution placeholders (`{ad_network}`, `{campaign}`, etc.) | URL template configuration; requires building your own receiving endpoint |
| **CSV uploads / Cloud storage upload** | Scheduled CSV uploads to your S3/GCS/Azure | Hourly / daily | Raw data reports (installs, events, etc., with configurable columns) | help.adjust.com "Raw data export" section; Live/Pause control |
| **S2S (server-to-server)** | Reverse channel: your service sends events to Adjust | Real-time | Reporting your own server-side events | Opposite direction from integrating attribution data, but shows the same account can do both ways |

### 2.3 Airbridge

- **Cloud storage export**: periodically exports raw data to AWS S3 / Google Cloud Storage, same pattern as the two above.

### 2.4 Common Conclusions

1. **All mainstream MMPs support two delivery forms**: real-time Webhook (Push API / real-time callbacks) and scheduled delivery to your own cloud storage (Data Locker / CSV uploads / cloud storage export).
2. Raw data (not aggregated reports) channels are generally **locked behind higher-tier paid plans** — the external cost is mainly the MMP-side subscription, not development effort.
3. The data alignment key is the MMP app identifier (`app_id` / app token) plus the device ID (IDFA/GAID or its hash). A **MMP app ↔ `game_id + environment` mapping configuration** is needed before data can land within oddsmaker's isolation contract.
4. Ad spend (cost) is typically not in the raw install data but in the MMP's campaign reporting API — a complete CPI/ROAS pipeline requires an additional report-pulling job.

## 3. Integration Path Comparison (from the oddsmaker perspective)

### Option A: Real-time Webhook [self-built receiving endpoint]

MMP Push API / callbacks hit a new ingestion endpoint on the Gateway or control service.

- Pros: second-level freshness; can feed fraud detection in real time (channel fraud identification).
- Cons: must handle signature verification, replay, idempotency, and rate limiting; retry semantics differ across MMPs; +1 to the exposed endpoint surface. Tensions with the P0 security fix (Gateway consolidated pre-validation) direction.

### Option B (recommended): Scheduled cloud storage export [load job → ClickHouse]

The MMP periodically writes to S3/GCS (Data Locker / CSV uploads); ops uses **the same object-storage sync mechanism as the P7-4 export directory** to bring the files to the oddsmaker side; add a scheduled load job to parse and ingest.

- Pros: symmetric with the existing architecture (P7-4 already established the "export directory + manifest + ops sync" archive pattern; reading the same directory scheme in reverse works); batch is replayable and reconcilable; no new public endpoint; parse failures can be fixed and re-run.
- Cons: hourly latency (entirely sufficient for UA analysis scenarios); depends on the ops object-storage sync job.

### Option C: MMP S2S postback masquerading as a channel event hitting the Gateway

Treat the MMP postback as an in-app event and route it directly through the SDK event channel.

- Not recommended: pollutes the own event contract (ambiguous `event_type` semantics), MMP postback attribution fields cannot fit the `attribution Map` spec, and it loses the room to evolve an independent table structure.

**Conclusion: initiate the project with Option B as the primary path; if real-time anti-fraud is needed later, add Option A as an increment.**

## 4. Table-creation Draft (ClickHouse)

```sql
-- MMP 安装归因（方案 B 主表）
CREATE TABLE IF NOT EXISTS attribution_installs
(
    game_id        String,
    environment    LowCardinality(String),
    mmp            LowCardinality(String),   -- appsflyer / adjust / airbridge / ...
    mmp_app_id     String,                   -- MMP 侧 app 标识
    install_time   DateTime,
    event_date     Date MATERIALIZED toDate(install_time),
    subject        String,                   -- 与 events 相同 SUBJECT 口径（player_id→user_id→device_id 回退）
    device_id      String,
    user_id        String,
    is_organic     UInt8,
    match_type     LowCardinality(String),   -- click / impression / srn / probabilistic ...
    touch_time     DateTime,
    media_source   String,                   -- 归一化到 events.ad_network 口径
    agency         String,
    campaign       String,
    adset          String,
    ad             String,
    keyword        String,
    country        LowCardinality(String),
    device_type    LowCardinality(String),
    platform       LowCardinality(String),
    app_version    String,
    raw            Map(String, String),      -- MMP 原始行兜底（与 events.attribution 同思路）
    ingest_batch   String,                   -- 加载批次（文件名/manifest id），支持按批次重放删除
    ingested_at    DateTime DEFAULT now()
)
ENGINE = ReplacingMergeTree(ingested_at)
PARTITION BY (game_id, environment, toYYYYMM(event_date))
ORDER BY (game_id, environment, mmp_app_id, subject, install_time);

-- 归因 in-app 事件（可选二期）：结构同上，event_name/revenue 两列替换 campaign 侧列
```

Companion (Postgres, control side):

- `mmp_connections` table: `game_id + environment` ↔ `mmp + mmp_app_id` mapping, file path patterns, field mapping version, enable/disable. Permissions reuse `export:read` / add new `mmp:manage`.
- Load job: scan directory → parse by manifest/filename → batch idempotency (dedup on `ingest_batch`) → failure alert.

## 5. Cost and Effort Estimate

> **Conclusion:** the only hard gate is the external MMP account's raw data export permission; the minimum viable set for Option B is about 5–7 person-days, and CPI/ROAS phase 2 adds another 4–6 person-days.

| Item | Estimate |
|---|---|
| External dependency | **Heavy**: requires a real MMP account + raw data export plan (neither Data Locker nor CSV uploads are free-tier); without an account, the whole pipeline cannot be validated |
| Development effort (Option B minimum viable set) | ~5–7 person-days: DDL + `mmp_connections` CRUD + load job (one parser each for CSV/JSON) + one channel install distribution report |
| Phase 2 (CPI/ROAS) | +4–6 person-days: campaign spend report API pull + attributed revenue join; requires MMP report API permission |
| Risk | MMP fields vary across vendors and versions (AppsFlyer/Adjust have different column names) — parsers must be isolated by the `mmp` dimension + `raw` Map fallback |

## 6. Go / No-Go Recommendation

**Conditional initiation (into the P8 roadmap):**

- Trigger condition: obtain raw data export permission from any MMP (either Data Locker or CSV uploads is sufficient to start).
- Startup scope: Option B minimum viable set + install attribution channel distribution report; CPI/ROAS and other spend data integration goes to phase 2.
- Explicitly not doing (consistent with competitive analysis §4): direct ad platform integration (pulling data directly from Facebook/Google Ads APIs), MMP aggregated dashboard replication, self-developed mobile attribution SDK.
- Before the trigger condition is met, the P7 series closes here; subsequent priority returns to filling in test coverage and polishing ops tooling.

## 7. Referenceable Analysis (item-by-item judgment and landing point)

> **Conclusion:** the path is set — Option B as the primary path + conditional initiation (P8), waiting for any MMP raw data export permission to unlock; 3 referenceable items (landing points all at the table/job level), 1 item for long-term reference, and 2 items not applicable.

| Judgment | Item | Why | Landing point in this repo |
|------|------|--------|----------|
| Referenceable | Option B: scheduled cloud storage export → load job (§3) | Symmetric with the "export directory + manifest + ops sync" mechanism already established in P7-4; batch is replayable and reconcilable; no new public endpoint | After P8 unlocks, create `attribution_installs` + load job per the §4 DDL; the todo.md boundary "P8 MMP attribution integration stays paused" is the tracking slot for this item |
| Referenceable | MMP app ↔ `game_id + environment` mapping configuration (§2.4.3) | The data alignment key must land within this repo's isolation contract, otherwise channel data floats outside the game/environment boundary | Control-side `mmp_connections` table (§4 companion): mapping, file path patterns, field mapping version, enable/disable; permissions reuse `export:read` / add new `mmp:manage` |
| Referenceable | Idempotency design in the table draft: `ingest_batch` dedup + `raw` Map fallback (§4) | MMP fields vary across vendors and versions; batch idempotency lets parse failures be fixed and replayed; the raw fallback absorbs column-name drift | New table DDL in `schema/sql/clickhouse`; batch definition aligns with the existing export manifest; `media_source` normalizes to the `events.ad_network` definition |
| Worth borrowing | Option A: real-time Webhook (Push API / callbacks, §3) | Second-level freshness can feed channel fraud risk control (fake volume detection) | Long-term increment: open an ingestion endpoint only when real-time anti-fraud is genuinely needed; the tension with the P0 security direction (Gateway consolidated pre-validation) needs review first, not part of the P8 launch |
| Not applicable | Option C: postback masquerading as a channel event via the Gateway (§3) | Pollutes the `event_type` contract; MMP attribution fields cannot fit the `attribution Map` spec | Not doing (per §3 conclusion) |
| Not applicable | Direct ad platform integration / MMP aggregated dashboard replication / self-developed attribution SDK (§6) | Beyond the analytics platform's responsibilities; consistent with the competitive analysis §4 explicit do-not-do list | Not doing (§6) |

## 8. References

- AppsFlyer dev docs: Push API, Data Locker, Raw Data Pull API V2, Postbacks by date API (dev.appsflyer.com)
- Adjust help center: Raw data export → Cloud storage upload (help.adjust.com); S2S and callbacks (dev.adjust.com)
- Airbridge docs: Cloud storage export (scheduled S3/GCS export)
- This repository: `docs/competitive-analysis.md` (gap matrix and do-not-follow list), `schema/sql/clickhouse/schema.sql` (existing attribution fields on the events table)
