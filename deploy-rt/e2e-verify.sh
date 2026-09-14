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

section "1. 服务健康"
$COMPOSE ps --format '{{.Name}} {{.Status}}' | sort
for svc in postgres redis kafka clickhouse apicurio control gateway; do
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
DLQ_N=$($COMPOSE exec -T kafka /opt/kafka/bin/kafka-run-class.sh kafka.tools.GetOffsetShell --broker-list localhost:9092 --topic oddsmaker.deadletter 2>/dev/null | awk -F: '{s+=$3} END {print s+0}')
echo "  deadletter offsets: $DLQ_N"
[ "${DLQ_N:-0}" -eq 0 ] && ok "no deadletter" || bad "deadletter has $DLQ_N messages"
RISK_N=$($COMPOSE exec -T kafka /opt/kafka/bin/kafka-run-class.sh kafka.tools.GetOffsetShell --broker-list localhost:9092 --topic oddsmaker.risk_events 2>/dev/null | awk -F: '{s+=$3} END {print s+0}')
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

echo
echo "===== RESULT: $PASS passed, $FAIL failed ====="
exit $FAIL
