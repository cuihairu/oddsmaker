#!/usr/bin/env bash
# Oddsmaker 基础链路端到端验证（在 runner-docker 上执行）
# 覆盖：服务健康 → Flyway 迁移 → 游戏/环境/Key 控制面 → Gateway 事件接入 → Kafka 落盘
set -uo pipefail
cd "$(dirname "$0")"
COMPOSE="docker compose -f docker-compose.e2e.yml"
PASS=0; FAIL=0
ok()  { echo "  PASS: $1"; PASS=$((PASS+1)); }
bad() { echo "  FAIL: $1"; FAIL=$((FAIL+1)); }
section() { echo; echo "== $1 =="; }

ADMIN="x-admin-token: e2e-admin-token"
CTRL=http://localhost:8085
GW=http://localhost:18080
CH="curl -s -u oddsmaker:oddsmaker"

dlq_count() {
  # Kafka 3.7 的 GetOffsetShell 已迁到 org.apache.kafka.tools，旧 kafka.tools 路径 ClassNotFoundException
  # （错误走 stderr、stdout 为空，awk 兜底会静默输出 0 造成假阳性）——直接用 3.x 的 kafka-get-offsets.sh
  $COMPOSE exec -T kafka /opt/kafka/bin/kafka-get-offsets.sh \
    --bootstrap-server localhost:9092 --topic oddsmaker.deadletter 2>/dev/null \
    | awk -F: '{s+=$3} END {print s+0}'
}

section "0. DLQ 基线（历史残留不计入本次判定）"
DLQ_BASE=$(dlq_count)
echo "  deadletter baseline: ${DLQ_BASE:-0}"

section "1. 服务健康"
$COMPOSE ps --format '{{.Name}} {{.Status}}' | sort
# configurable-funnels 不在此列：无漏斗配置时按设计直接退出（第 15 节铺配置后重启再验）
for svc in postgres redis kafka clickhouse apicurio control gateway enrich sessions retention funnels identity risk; do
  st=$($COMPOSE ps --format '{{.Name}} {{.Status}}' | grep "^e2e-$svc " | grep -c "(healthy)")
  [ "$st" -ge 1 ] && ok "$svc healthy" || bad "$svc not healthy"
done

section "2. Flyway 迁移（PG）"
$COMPOSE exec -T postgres psql -U oddsmaker -d oddsmaker -tAc \
  "select count(*)||' migrations, latest='||coalesce(max(version),'none') from flyway_schema_history;" 2>/dev/null \
  | xargs -I{} echo "  {}" && ok "flyway history readable" || bad "flyway history missing"
$COMPOSE exec -T postgres psql -U oddsmaker -d oddsmaker -tAc \
  "select count(*) from information_schema.tables where table_schema='public';" 2>/dev/null | xargs -I{} echo "  public tables: {}"

section "3. 控制面：游戏 → 环境 → API Key"
CREATE_GAME=$(curl -sS -X POST "$CTRL/api/games" -H "$ADMIN" -H 'content-type: application/json' -d '{"id":"e2e_game","name":"E2E Game"}' 2>&1 || true)
echo "  createGame: $(echo "$CREATE_GAME" | head -c 200)"
echo "$CREATE_GAME" | grep -q "e2e_game" && ok "create game" || bad "create game: $CREATE_GAME"

CREATE_ENV=$(curl -sS -X POST "$CTRL/api/games/e2e_game/environments" -H "$ADMIN" -H 'content-type: application/json' -d '{"name":"dev"}' 2>&1 || true)
echo "  createEnv: $(echo "$CREATE_ENV" | head -c 200)"
echo "$CREATE_ENV" | grep -qi "dev\|error\|exist" && ok "create env (or already exists)" || bad "create env: $CREATE_ENV"

ENV_ID=$($COMPOSE exec -T postgres psql -U oddsmaker -d oddsmaker -tAc "select id from game_environments where game_id='e2e_game' and name='dev' limit 1;" 2>/dev/null | tr -d '[:space:]')
[ -z "$ENV_ID" ] && ENV_ID="dev"
CREATE_KEY=$(curl -sS -X POST "$CTRL/api/keys" -H "$ADMIN" -H 'content-type: application/json' -d "{\"gameId\":\"e2e_game\",\"environmentId\":\"$ENV_ID\",\"name\":\"e2e-key\",\"keyRole\":\"client\"}" 2>&1 || true)
echo "  createKey: $(echo "$CREATE_KEY" | head -c 300)"
API_KEY=$(echo "$CREATE_KEY" | sed -n 's/.*"apiKey"\s*:\s*"\([^"]*\)".*/\1/p')
SECRET=$(echo "$CREATE_KEY" | sed -n 's/.*"secret"\s*:\s*"\([^"]*\)".*/\1/p')
[ -n "$API_KEY" ] && ok "api key created: $API_KEY" || bad "api key creation failed"

section "4. Gateway 事件接入"
TS=$(date +%s000)
NDJSON=$(printf '{"event_id":"e2e_evt_0001","event_type":"progression","event_name":"level_start","game_id":"e2e_game","environment":"dev","device_id":"d1","ts_client":%s,"props":{"level":1}}\n{"event_id":"e2e_evt_0002","event_type":"progression","event_name":"level_complete","game_id":"e2e_game","environment":"dev","device_id":"d1","ts_client":%s,"props":{"level":1,"stars":3}}' "$TS" "$((TS+1000))")
BATCH=$(printf '%s' "$NDJSON" | curl -sS -X POST "$GW/v1/batch" -H "x-api-key: $API_KEY" -H "content-type: application/x-ndjson" --data-binary @- 2>&1)
echo "  batch response: $(echo "$BATCH" | head -c 300)"
echo "$BATCH" | grep -q "accepted" && ok "events accepted" || bad "batch: $BATCH"

section "5. Kafka 落盘（events_raw）"
sleep 2
RAW=$($COMPOSE exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic oddsmaker.events_raw --from-beginning --max-messages 1 --timeout-ms 15000 2>/dev/null | head -c 60 | od -An -tx1 | tr -d ' \n')
if [ -n "$RAW" ]; then ok "events_raw has message (hex head: $(echo "$RAW" | head -c 32)...)"; else bad "events_raw empty"; fi
DLQ_N=$(dlq_count)
echo "  deadletter offsets: ${DLQ_N:-0} (baseline ${DLQ_BASE:-0})"
[ "${DLQ_N:-0}" -eq "${DLQ_BASE:-0}" ] && ok "no new deadletter" || bad "deadletter grew by $((DLQ_N-DLQ_BASE)) messages"
RISK_N=$($COMPOSE exec -T kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic oddsmaker.risk_events 2>/dev/null | awk -F: '{s+=$3} END {print s+0}')
echo "  risk_events offsets: ${RISK_N:-0}"

section "6. ClickHouse 落库与 schema"
CHTBLS=$($COMPOSE exec -T clickhouse clickhouse-client --query "select count() from system.tables where database='default'" 2>/dev/null)
echo "  default tables: ${CHTBLS:-?}"
[ "${CHTBLS:-0}" -ge 1 ] && ok "clickhouse schema initialized" || bad "clickhouse schema empty"

section "7. 审计与密钥落库"
KEYROWS=$($COMPOSE exec -T postgres psql -U oddsmaker -d oddsmaker -tAc "select count(*) from api_keys where game_id='e2e_game';" 2>/dev/null)
echo "  api_keys rows for e2e_game: ${KEYROWS:-?}"
AUDROWS=$($COMPOSE exec -T postgres psql -U oddsmaker -d oddsmaker -tAc "select count(*) from audit_logs;" 2>/dev/null)
echo "  audit_logs rows: ${AUDROWS:-?}"
[ "${KEYROWS:-0}" -ge 1 ] && ok "api key persisted" || bad "api key not persisted"

section "8. Flink 消费落库（events_raw → enrich → ClickHouse）"
ENRICH_OK=0
# events-enrich 以 latest 起点订阅；job 刚启动时可能尚未订阅完成，最多重试 3 轮（每轮新 event_id）
for attempt in 1 2 3; do
  EVT_ID="verify_flink_$(date +%s)_$attempt"
  TS=$(date +%s000)
  BODY=$(printf '{"event_id":"%s","event_type":"progression","event_name":"flink_verify","game_id":"e2e_game","environment":"dev","device_id":"verify_d","ts_client":%s,"user_agent":"verify-agent/1.0","props":{"attempt":%s}}' "$EVT_ID" "$TS" "$attempt")
  BATCH=$(printf '%s' "$BODY" | curl -sS -X POST "$GW/v1/batch" -H "x-api-key: $API_KEY" -H "content-type: application/x-ndjson" --data-binary @- 2>&1)
  echo "$BATCH" | grep -q "accepted" || { bad "attempt $attempt not accepted: $BATCH"; continue; }
  # JdbcSink 批间隔 200ms，留出反序列化/enrich/落库时间
  for i in $(seq 1 15); do
    FOUND=$($CH "http://localhost:18123/?query=select+count()+from+events+where+event_id='$EVT_ID'" 2>/dev/null)
    [ "${FOUND:-0}" -ge 1 ] && break
    sleep 2
  done
  if [ "${FOUND:-0}" -ge 1 ]; then
    PROPS=$($CH "http://localhost:18123/?query=select+props_json+from+events+where+event_id='$EVT_ID'+limit+1" 2>/dev/null)
    echo "  landed: $EVT_ID props=$PROPS"
    ENRICH_OK=1
    break
  fi
  echo "  attempt $attempt: not landed in 30s, retrying with new id"
  sleep 5
done
[ "$ENRICH_OK" -eq 1 ] && ok "event landed in ClickHouse via Flink" || bad "no event landed in ClickHouse"
[ "$ENRICH_OK" -eq 1 ] && { echo "$PROPS" | grep -q "ua_family" && ok "UA enrich merged into props_json" || bad "UA enrich missing: $PROPS"; }
DLQ_N=$(dlq_count)
[ "${DLQ_N:-0}" -eq "${DLQ_BASE:-0}" ] && ok "no new deadletter after flink verification" || bad "deadletter grew to $DLQ_N"

section "9. Flink sessions 聚合落库（events_raw → session window → ClickHouse）"
# session 窗口（e2e gap=1min、watermark 容差=0）只在后续事件把 watermark 推过 窗口末+gap 后 fire：
# 先发同 device 两条（批1），等 gap+缓冲，再发一条（批2）推进 watermark，前一个窗口才落库
SESSIONS_OK=0
for attempt in 1 2; do
  DID="verify_sess_$(date +%s)_$attempt"
  TS=$(date +%s000)
  B1=$(printf '{"event_id":"verify_ses_a_%s","event_type":"progression","event_name":"level_start","game_id":"e2e_game","environment":"dev","device_id":"%s","ts_client":%s}\n{"event_id":"verify_ses_b_%s","event_type":"progression","event_name":"level_end","game_id":"e2e_game","environment":"dev","device_id":"%s","ts_client":%s}' "$attempt" "$DID" "$TS" "$attempt" "$DID" "$((TS+2000))")
  R1=$(printf '%s' "$B1" | curl -sS -X POST "$GW/v1/batch" -H "x-api-key: $API_KEY" -H "content-type: application/x-ndjson" --data-binary @- 2>&1)
  echo "$R1" | grep -q "accepted" || { bad "sessions batch1 not accepted: $R1"; continue; }
  # 等窗口末（批1 末事件 + gap 60s）过去
  sleep 66
  B2=$(printf '{"event_id":"verify_ses_c_%s","event_type":"progression","event_name":"level_start","game_id":"e2e_game","environment":"dev","device_id":"%s","ts_client":%s}' "$attempt" "$DID" "$(date +%s000)")
  R2=$(printf '%s' "$B2" | curl -sS -X POST "$GW/v1/batch" -H "x-api-key: $API_KEY" -H "content-type: application/x-ndjson" --data-binary @- 2>&1)
  echo "$R2" | grep -q "accepted" || { bad "sessions batch2 not accepted: $R2"; continue; }
  for i in $(seq 1 10); do
    SEVTS=$($CH "http://localhost:18123/?query=select+events+from+sessions+where+device_id='$DID'+limit+1" 2>/dev/null)
    [ "${SEVTS:-0}" -ge 1 ] && break
    sleep 2
  done
  if [ "${SEVTS:-0}" -ge 1 ]; then
    echo "  session landed: device=$DID events=$SEVTS"
    SESSIONS_OK=1
    break
  fi
  echo "  attempt $attempt: session not landed, retrying"
done
[ "$SESSIONS_OK" -eq 1 ] && ok "session window fired and landed in ClickHouse" || bad "no session landed in ClickHouse"

echo
echo "== 10. CH schema 幂等重放（initdb 只在空卷跑，缺失表在此自愈）=="
# 只重放幂等且覆盖全部 Flink job 目标表的三个文件；
# 目录里 ltv/crash/queries* 等含裸 DDL，重放会报表已存在，不纳入
# 脚本 cd 在 deploy-rt 下，schema 在仓库根的上一级
for f in ../schema/sql/clickhouse/schema.sql ../schema/sql/clickhouse/analytics.sql ../schema/sql/clickhouse/configurable-funnels-schema.sql; do
  if $COMPOSE exec -T clickhouse clickhouse-client --multiquery < "$f" >/dev/null 2>&1; then
    ok "replay $(basename "$f")"
  else
    bad "replay $(basename "$f") failed"
  fi
done

section "11. Flink retention 落库（events_raw → 首事件即 emit d=0 → retention_daily）"
# retention_daily 无 device 维度，用 d=0 用户数基线增长判定（表存在且本次事件真实落库）
RET_BASE=$($CH "http://localhost:18123/?query=select+sum(users)+from+retention_daily+where+d=0" 2>/dev/null)
RET_BASE=${RET_BASE:-0}
RET_OK=0
for attempt in 1 2; do
  DID="verify_ret_$(date +%s)_$attempt"
  TS=$(date +%s000)
  BODY=$(printf '{"event_id":"verify_ret_a_%s","event_type":"progression","event_name":"level_start","game_id":"e2e_game","environment":"dev","device_id":"%s","ts_client":%s}' "$attempt" "$DID" "$TS")
  R=$(printf '%s' "$BODY" | curl -sS -X POST "$GW/v1/batch" -H "x-api-key: $API_KEY" -H "content-type: application/x-ndjson" --data-binary @- 2>&1)
  echo "$R" | grep -q "accepted" || { bad "retention batch not accepted: $R"; continue; }
  for i in $(seq 1 15); do
    RET=$($CH "http://localhost:18123/?query=select+sum(users)+from+retention_daily+where+d=0" 2>/dev/null)
    [ "${RET:-0}" -gt "$RET_BASE" ] && break
    sleep 2
  done
  if [ "${RET:-0}" -gt "$RET_BASE" ]; then
    echo "  retention landed: d=0 users ${RET_BASE} -> ${RET}"
    RET_OK=1
    break
  fi
done
[ "$RET_OK" -eq 1 ] && ok "retention d=0 row landed in ClickHouse" || bad "no retention row in ClickHouse"

section "12. Flink funnels 落库（level_start → started=1；level_complete → completed=1）"
# funnels_2step 同样无 device 维度，started/completed 双基线增长判定
FUN_BASE_S=$($CH "http://localhost:18123/?query=select+sum(started)+from+funnels_2step+where+event_date=today()" 2>/dev/null)
FUN_BASE_C=$($CH "http://localhost:18123/?query=select+sum(completed)+from+funnels_2step+where+event_date=today()" 2>/dev/null)
FUN_BASE_S=${FUN_BASE_S:-0}; FUN_BASE_C=${FUN_BASE_C:-0}
DID="verify_fun_$(date +%s)"
TS=$(date +%s000)
FBODY=$(printf '{"event_id":"verify_fun_a_%s","event_type":"progression","event_name":"level_start","game_id":"e2e_game","environment":"dev","device_id":"%s","ts_client":%s}\n{"event_id":"verify_fun_b_%s","event_type":"progression","event_name":"level_complete","game_id":"e2e_game","environment":"dev","device_id":"%s","ts_client":%s}' "$DID" "$DID" "$TS" "$DID" "$DID" "$((TS+2000))")
R=$(printf '%s' "$FBODY" | curl -sS -X POST "$GW/v1/batch" -H "x-api-key: $API_KEY" -H "content-type: application/x-ndjson" --data-binary @- 2>&1)
echo "$R" | grep -q "accepted" || bad "funnels batch not accepted: $R"
STARTED=0; COMPLETED=0
for i in $(seq 1 15); do
  STARTED=$($CH "http://localhost:18123/?query=select+sum(started)+from+funnels_2step+where+event_date=today()" 2>/dev/null)
  COMPLETED=$($CH "http://localhost:18123/?query=select+sum(completed)+from+funnels_2step+where+event_date=today()" 2>/dev/null)
  [ "${STARTED:-0}" -gt "$FUN_BASE_S" ] && [ "${COMPLETED:-0}" -gt "$FUN_BASE_C" ] && break
  sleep 2
done
if [ "${STARTED:-0}" -gt "$FUN_BASE_S" ] && [ "${COMPLETED:-0}" -gt "$FUN_BASE_C" ]; then
  echo "  funnel landed: started ${FUN_BASE_S} -> ${STARTED}, completed ${FUN_BASE_C} -> ${COMPLETED}"
  ok "funnel started+completed rows landed"
else
  bad "funnel rows missing (started=${STARTED:-0}/${FUN_BASE_S} completed=${COMPLETED:-0}/${FUN_BASE_C})"
fi

section "13. Flink identity-merge 落库（identity 事件 → CH identities + Kafka identity_events）"
IDT_OK=0
UID_E2E="verify_user_$(date +%s)"
TS=$(date +%s000)
IBODY=$(printf '{"event_id":"verify_idt_a_%s","event_type":"identity","event_name":"signup","game_id":"e2e_game","environment":"dev","device_id":"verify_idt_d","user_id":"%s","ts_client":%s}' "$UID_E2E" "$UID_E2E" "$TS")
R=$(printf '%s' "$IBODY" | curl -sS -X POST "$GW/v1/batch" -H "x-api-key: $API_KEY" -H "content-type: application/x-ndjson" --data-binary @- 2>&1)
echo "$R" | grep -q "accepted" || bad "identity batch not accepted: $R"
for i in $(seq 1 15); do
  IDT=$($CH "http://localhost:18123/?query=select+identity_id+from+identities+where+user_id='$UID_E2E'+limit+1" 2>/dev/null)
  [ -n "$IDT" ] && break
  sleep 2
done
if [ -n "$IDT" ]; then
  echo "  identity landed: $IDT"
  ok "identity row landed in ClickHouse"
  IDT_OK=1
else
  bad "no identity row in ClickHouse"
fi
IDT_TOPIC=$($COMPOSE exec -T kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic oddsmaker.identity_events 2>/dev/null | awk -F: '{s+=$3} END {print s+0}')
echo "  identity_events offsets: ${IDT_TOPIC:-0}"
[ "${IDT_TOPIC:-0}" -ge 1 ] && ok "identity_events topic has messages" || bad "identity_events topic empty"

section "14. Flink risk 落库（THRESHOLD 规则：resource_amount 超阈值立即触发）"
RISK_BASE=$($COMPOSE exec -T kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic oddsmaker.risk_events 2>/dev/null | awk -F: '{s+=$3} END {print s+0}')
EVT_ID="verify_risk_$(date +%s)"
TS=$(date +%s000)
RBODY=$(printf '{"event_id":"%s","event_type":"economy","event_name":"resource_flow","game_id":"e2e_game","environment":"dev","device_id":"verify_risk_d","user_id":"verify_risk_u","resource_id":"gold","resource_amount":999999,"flow_type":"source","ts_client":%s}' "$EVT_ID" "$TS")
R=$(printf '%s' "$RBODY" | curl -sS -X POST "$GW/v1/batch" -H "x-api-key: $API_KEY" -H "content-type: application/x-ndjson" --data-binary @- 2>&1)
echo "$R" | grep -q "accepted" || bad "risk batch not accepted: $R"
RISK_HIT=0
for i in $(seq 1 15); do
  RH=$($CH "http://localhost:18123/?query=select+rule_id,risk_type,severity+from+risk_events+where+source_event_id='$EVT_ID'+limit+1+FORMAT+TSV" 2>/dev/null)
  [ -n "$RH" ] && break
  sleep 2
done
if [ -n "$RH" ]; then
  echo "  risk event landed: $RH"
  ok "risk event landed in ClickHouse"
  RISK_HIT=1
else
  bad "no risk event in ClickHouse"
fi
RISK_N=$($COMPOSE exec -T kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic oddsmaker.risk_events 2>/dev/null | awk -F: '{s+=$3} END {print s+0}')
echo "  risk_events offsets: ${RISK_N:-0} (baseline ${RISK_BASE:-0})"
[ "${RISK_N:-0}" -gt "${RISK_BASE:-0}" ] && ok "risk_events topic grew" || bad "risk_events topic did not grow"

# 契约回归：risk 类埋点必须走 events_raw 全链（曾因 gateway 改道 Avro 到 risk_events，
# 造成 RiskJob 收不到埋点 + control JSON 消费者每条报错）
TAG_ID="verify_risktag_$(date +%s)"
TS=$(date +%s000)
TBODY=$(printf '{"event_id":"%s","event_type":"risk","event_name":"user_risk_signal","game_id":"e2e_game","environment":"dev","device_id":"verify_tag_d","ts_client":%s}' "$TAG_ID" "$TS")
R=$(printf '%s' "$TBODY" | curl -sS -X POST "$GW/v1/batch" -H "x-api-key: $API_KEY" -H "content-type: application/x-ndjson" --data-binary @- 2>&1)
echo "$R" | grep -q "accepted" || bad "risk-tagged batch not accepted: $R"
TAG_LANDED=""
for i in $(seq 1 15); do
  TAG_LANDED=$($CH "http://localhost:18123/?query=select+count()+from+events+where+event_id='$TAG_ID'" 2>/dev/null)
  [ "${TAG_LANDED:-0}" -ge 1 ] && break
  sleep 2
done
[ "${TAG_LANDED:-0}" -ge 1 ] && ok "risk-tagged event landed via events_raw (no detour)" || bad "risk-tagged event missing in CH events"
TAG_TOPIC=$($COMPOSE exec -T kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic oddsmaker.risk_events 2>/dev/null | awk -F: '{s+=$3} END {print s+0}')
echo "  risk_events offsets after risk-tagged event: ${TAG_TOPIC:-0} (expect ${RISK_N:-0})"
[ "${TAG_TOPIC:-0}" -eq "${RISK_N:-0}" ] && ok "gateway no longer writes Avro into risk_events" || bad "risk_events grew without a threshold hit (${RISK_N} -> ${TAG_TOPIC})"

section "15. Flink configurable funnels（control PG 配置 → 加载 → funnels_configurable 落库）"
# 幂等铺配置：control 真实迁移（V0.3.3）建的 funnel_analyses / funnel_steps 表。
# job 启动时一次性加载，所以插完配置要 force-recreate 该容器
CFG_SEED=$($COMPOSE exec -T postgres psql -U oddsmaker -d oddsmaker -v ON_ERROR_STOP=1 <<'SQL'
DELETE FROM funnel_steps WHERE funnel_analysis_id = 'e2e_funnel_cfg';
DELETE FROM funnel_analyses WHERE id = 'e2e_funnel_cfg';
INSERT INTO funnel_analyses (id, game_id, name, display_name, funnel_type, window_type, window_size, total_steps, status, enable_auto_calc, max_completion_time)
VALUES ('e2e_funnel_cfg', 'e2e_game', 'e2e-configurable-funnel', 'E2E Configurable Funnel', 'SEQUENTIAL', 'fixed', 7, 2, 'ACTIVE', FALSE, 86400);
INSERT INTO funnel_steps (id, funnel_analysis_id, funnel_id, step_order, name, event_name, display_name, status)
VALUES ('e2e_fs1', 'e2e_funnel_cfg', 'e2e_funnel_cfg', 1, 'Level Start', 'level_start', 'Level Start', 'ACTIVE'),
       ('e2e_fs2', 'e2e_funnel_cfg', 'e2e_funnel_cfg', 2, 'Level Complete', 'level_complete', 'Level Complete', 'ACTIVE');
SQL
) 2>&1
if [ $? -eq 0 ]; then
  echo "  config seeded: $(echo "$CFG_SEED" | tail -1)"
  ok "funnel config seeded in control PG"
else
  bad "funnel config seeding failed: $CFG_SEED"
fi

$COMPOSE up -d --force-recreate configurable-funnels-job >/dev/null 2>&1
CFG_READY=0
for i in $(seq 1 30); do
  st=$($COMPOSE ps --format '{{.Name}} {{.Status}}' | grep '^e2e-configurable-funnels ' | grep -c '(healthy)')
  [ "$st" -ge 1 ] && { CFG_READY=1; break; }
  sleep 2
done
[ "$CFG_READY" -eq 1 ] && ok "configurable-funnels job healthy after reload" || bad "configurable-funnels job not healthy"

CFG_BASE=$($CH "http://localhost:18123/?query=select+sum(users)+from+funnels_configurable+where+funnel_id='e2e_funnel_cfg'+and+step=1" 2>/dev/null)
CFG_BASE=${CFG_BASE:-0}
CFD="verify_cfg_$(date +%s)"
TS=$(date +%s000)
CBODY=$(printf '{"event_id":"verify_cfg_a_%s","event_type":"progression","event_name":"level_start","game_id":"e2e_game","environment":"dev","device_id":"%s","ts_client":%s}\n{"event_id":"verify_cfg_b_%s","event_type":"progression","event_name":"level_complete","game_id":"e2e_game","environment":"dev","device_id":"%s","ts_client":%s}' "$CFD" "$CFD" "$TS" "$CFD" "$CFD" "$((TS+2000))")
R=$(printf '%s' "$CBODY" | curl -sS -X POST "$GW/v1/batch" -H "x-api-key: $API_KEY" -H "content-type: application/x-ndjson" --data-binary @- 2>&1)
echo "$R" | grep -q "accepted" || bad "configurable batch not accepted: $R"
CFG_STEP1=0; CFG_STEP2=0
for i in $(seq 1 15); do
  CFG_STEP1=$($CH "http://localhost:18123/?query=select+sum(users)+from+funnels_configurable+where+funnel_id='e2e_funnel_cfg'+and+step=1" 2>/dev/null)
  CFG_STEP2=$($CH "http://localhost:18123/?query=select+sum(users)+from+funnels_configurable+where+funnel_id='e2e_funnel_cfg'+and+step=2" 2>/dev/null)
  [ "${CFG_STEP1:-0}" -gt "$CFG_BASE" ] && [ "${CFG_STEP2:-0}" -ge 1 ] && break
  sleep 2
done
if [ "${CFG_STEP1:-0}" -gt "$CFG_BASE" ] && [ "${CFG_STEP2:-0}" -ge 1 ]; then
  echo "  configurable funnel landed: step1 ${CFG_BASE} -> ${CFG_STEP1}, step2=${CFG_STEP2}"
  ok "configurable funnel rows landed in ClickHouse"
else
  bad "configurable funnel rows missing (step1=${CFG_STEP1:-0}/${CFG_BASE} step2=${CFG_STEP2:-0})"
  docker logs e2e-configurable-funnels 2>&1 | grep -E "Loaded|No funnel|ERROR|Exception" | head -3
fi

echo
echo "===== RESULT: $PASS passed, $FAIL failed ====="
exit $FAIL
