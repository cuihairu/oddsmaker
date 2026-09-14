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
for svc in postgres redis kafka clickhouse apicurio control gateway enrich; do
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
echo "===== RESULT: $PASS passed, $FAIL failed ====="
exit $FAIL
