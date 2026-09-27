#!/usr/bin/env bash
# 起一个带 SSL listener 的单节点 KRaft broker（apache/kafka:3.7.0），供
# KafkaSourceSslBrokerE2ETest 跑单向 TLS（自签 CA）真实回路 + SASL_SSL 组合臂。
#
# 前置：先跑 ./gen-pki.sh（PKI 全在 OUT_DIR，本脚本只挂载 + 注入配置）。
# 清理：docker rm -f "$NAME"（一次性容器，用完即删；镜像可留）
#
# listener 布局（名字刻意不含下划线，见下方「为什么不用 SSL_HOST」）：
#   PLAINTEXT://:9092    容器内管理面（建 topic / 建 SCRAM 用户），不映射到宿主
#   CONTROLLER://:9093   KRaft controller
#   SSLHOST://:29097     单向 TLS（宿主 localhost:29097）——测试臂 ①②
#   SASLSSLHOST://:29098 SCRAM-SHA-256 over TLS（宿主 localhost:29098）——测试臂 ③
#
# 为什么不用 SSL_HOST 这种带下划线的 listener 名：镜像用 KAFKA_<NAME>_<PROP> 环境变量
# 注入 listener 级配置，下划线会被当成属性分隔符错位（上一轮 SASL 实测踩过：
# listener 级 JAAS env 失效，报找不到 sasl_host.KafkaServer）。本脚本改为
# broker 级 ssl.* 属性 + 挂载 jaas.conf + KAFKA_OPTS，避开该转换陷阱。
#
# 环境变量（默认值给本地 E2E 用）：
#   NAME / IMAGE / OUT_DIR / HOST_PORT / SASL_SSL_PORT / SASL_USER / SASL_PASSWORD
set -euo pipefail

NAME="${NAME:-oddsmaker-kafka-ssl-e2e}"
IMAGE="${IMAGE:-apache/kafka:3.7.0}"
OUT_DIR="${OUT_DIR:-/tmp/oddsmaker-kafka-ssl-e2e}"
HOST_PORT="${HOST_PORT:-29097}"
SASL_SSL_PORT="${SASL_SSL_PORT:-29098}"
SASL_USER="${SASL_USER:-dim-e2e}"
SASL_PASSWORD="${SASL_PASSWORD:-dim-e2e-secret}"
BROKER_PASS="${BROKER_PASS:-broker-secret}"

[ -f "$OUT_DIR/secrets/broker.p12" ] || { echo "缺 $OUT_DIR/secrets/broker.p12，先跑 ./gen-pki.sh"; exit 1; }
[ -f "$OUT_DIR/secrets/jaas.conf" ] || { echo "缺 $OUT_DIR/secrets/jaas.conf，先跑 ./gen-pki.sh"; exit 1; }

docker rm -f "$NAME" >/dev/null 2>&1 || true

docker run -d --name "$NAME" \
  --add-host kafka:127.0.0.1 \
  -p "${HOST_PORT}:${HOST_PORT}" -p "${SASL_SSL_PORT}:${SASL_SSL_PORT}" \
  -v "$OUT_DIR/secrets:/etc/kafka/secrets:ro" \
  -e KAFKA_NODE_ID=1 \
  -e KAFKA_PROCESS_ROLES=broker,controller \
  -e KAFKA_LISTENERS="PLAINTEXT://:9092,CONTROLLER://:9093,SSLHOST://:${HOST_PORT},SASLSSLHOST://:${SASL_SSL_PORT}" \
  -e KAFKA_ADVERTISED_LISTENERS="PLAINTEXT://kafka:9092,SSLHOST://localhost:${HOST_PORT},SASLSSLHOST://localhost:${SASL_SSL_PORT}" \
  -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP="CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,SSLHOST:SSL,SASLSSLHOST:SASL_SSL" \
  -e KAFKA_INTER_BROKER_LISTENER_NAME=PLAINTEXT \
  -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@kafka:9093 \
  -e KAFKA_LISTENER_NAME_SASLSSLHOST_SASL_ENABLED_MECHANISMS=SCRAM-SHA-256 \
  -e KAFKA_SSL_KEYSTORE_LOCATION=/etc/kafka/secrets/broker.p12 \
  -e KAFKA_SSL_KEYSTORE_TYPE=PKCS12 \
  -e KAFKA_SSL_KEYSTORE_PASSWORD="$BROKER_PASS" \
  -e KAFKA_SSL_KEY_PASSWORD="$BROKER_PASS" \
  -e KAFKA_SSL_CLIENT_AUTH=none \
  -e KAFKA_OPTS="-Djava.security.auth.login.config=/etc/kafka/secrets/jaas.conf" \
  -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 \
  -e KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0 \
  -e KAFKA_AUTO_CREATE_TOPICS_ENABLE=true \
  "$IMAGE" >/dev/null

echo "容器 $NAME 已起，等待 SSL listener 就绪（localhost:${HOST_PORT}）..."
for i in $(seq 1 60); do
  if docker exec "$NAME" bash -c 'exec 3<>/dev/tcp/127.0.0.1/'"${HOST_PORT}"'' 2>/dev/null; then
    break
  fi
  sleep 1
done

# broker 起来后才能建 SCRAM 用户（走容器内明文管理面，不经 TLS）
echo "建 SCRAM 用户 ${SASL_USER} ..."
docker exec "$NAME" /opt/kafka/bin/kafka-configs.sh \
  --bootstrap-server localhost:9092 \
  --alter --add-config "SCRAM-SHA-256=[password=${SASL_PASSWORD}]" \
  --entity-type users --entity-name "$SASL_USER" 2>&1 | tail -2

# 握手自检：带 CA 应 Verify return code: 0（失败就别跑测试了）
echo "SSL 握手自检（带 CA）："
openssl s_client -connect "localhost:${HOST_PORT}" -CAfile "$OUT_DIR/ca.crt" </dev/null 2>&1 \
  | grep -E 'Verify return code|subject=' || true

cat <<EOF

ready:
  SSL       bootstrap = localhost:${HOST_PORT}
  SASL_SSL  bootstrap = localhost:${SASL_SSL_PORT}  (SCRAM-SHA-256, user=${SASL_USER})
  truststores in ${OUT_DIR}: client-truststore.jks / .p12 / client-truststore-wrong.p12
EOF
