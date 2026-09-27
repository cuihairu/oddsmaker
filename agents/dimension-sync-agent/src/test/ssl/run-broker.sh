#!/usr/bin/env bash
# 起一个带 SSL listener 的单节点 KRaft broker（apache/kafka:3.7.0），供
# KafkaSourceSslBrokerE2ETest 跑单向 TLS（自签 CA）真实回路 + SASL_SSL 组合臂
# + mTLS 双向认证臂（require client auth）。
#
# 前置：先跑 ./gen-pki.sh（PKI 全在 OUT_DIR，本脚本只挂载 + 注入配置）。
# 清理：docker rm -f "$NAME"（一次性容器，用完即删；镜像可留）
#
# listener 布局（名字一律不含下划线，原因见下）：
#   PLAINTEXT://:9092    容器内管理面（建 topic / 建 SCRAM 用户），不映射到宿主
#   CONTROLLER://:9093   KRaft controller
#   SSLHOST://:29097     单向 TLS（宿主 localhost:29097）——测试臂 ①②
#   SASLSSLHOST://:29098 SCRAM-SHA-256 over TLS（宿主 localhost:29098）——测试臂 ③
#   MTLSHOST://:29099    mTLS 双向认证（listener 级 ssl.client.auth=required）——测试臂 ④⑤
#
# 为什么 listener 名一律不带下划线（MTLS_HOST / SSL_HOST 都会踩）：镜像把
# KAFKA_<X>_<Y> 转成 x.y（**全部**下划线换成点），故 KAFKA_LISTENER_NAME_MTLS_HOST_SSL_CLIENT_AUTH
# 落进 server.properties 是 listener.name.mtls.host.ssl.client.auth——broker 按第一个点切
# listener 名（得到 mtls，与 MTLS_HOST 不匹配），余下当成属性名（host.ssl.client.auth 是未知
# 属性），配置静默失效。上一轮 SASL 实测踩过同一坑（listener 级 JAAS env 失效，报找不到
# sasl_host.KafkaServer），当时改走 broker 级 ssl.* + 挂载 jaas.conf 绕过。
# 本轮 mTLS 必需 listener 级覆盖（broker 级 ssl.client.auth 要留 none，否则 29097/29098
# 两臂也一起要求客户端证书），所以用无下划线的 MTLSHOST 正面走通：
# KAFKA_LISTENER_NAME_MTLSHOST_SSL_CLIENT_AUTH → listener.name.mtlshost.ssl.client.auth ✓
# （实测 server.properties 与 broker 拒绝行为均生效，见文末自检）
#
# 环境变量（默认值给本地 E2E 用）：
#   NAME / IMAGE / OUT_DIR / HOST_PORT / SASL_SSL_PORT / MTLS_PORT
#   SASL_USER / SASL_PASSWORD / BROKER_PASS / TRUST_PASS
set -euo pipefail

NAME="${NAME:-oddsmaker-kafka-ssl-e2e}"
IMAGE="${IMAGE:-apache/kafka:3.7.0}"
OUT_DIR="${OUT_DIR:-/tmp/oddsmaker-kafka-ssl-e2e}"
HOST_PORT="${HOST_PORT:-29097}"
SASL_SSL_PORT="${SASL_SSL_PORT:-29098}"
MTLS_PORT="${MTLS_PORT:-29099}"
TRUST_PASS="${TRUST_PASS:-trust-secret}"
SASL_USER="${SASL_USER:-dim-e2e}"
SASL_PASSWORD="${SASL_PASSWORD:-dim-e2e-secret}"
BROKER_PASS="${BROKER_PASS:-broker-secret}"

[ -f "$OUT_DIR/secrets/broker.p12" ] || { echo "缺 $OUT_DIR/secrets/broker.p12，先跑 ./gen-pki.sh"; exit 1; }
[ -f "$OUT_DIR/secrets/jaas.conf" ] || { echo "缺 $OUT_DIR/secrets/jaas.conf，先跑 ./gen-pki.sh"; exit 1; }
[ -f "$OUT_DIR/secrets/broker-truststore.p12" ] || { echo "缺 $OUT_DIR/secrets/broker-truststore.p12，先跑 ./gen-pki.sh"; exit 1; }
[ -f "$OUT_DIR/client-keystore.p12" ] || { echo "缺 $OUT_DIR/client-keystore.p12，先跑 ./gen-pki.sh"; exit 1; }

docker rm -f "$NAME" >/dev/null 2>&1 || true

docker run -d --name "$NAME" \
  --add-host kafka:127.0.0.1 \
  -p "${HOST_PORT}:${HOST_PORT}" -p "${SASL_SSL_PORT}:${SASL_SSL_PORT}" -p "${MTLS_PORT}:${MTLS_PORT}" \
  -v "$OUT_DIR/secrets:/etc/kafka/secrets:ro" \
  -e KAFKA_NODE_ID=1 \
  -e KAFKA_PROCESS_ROLES=broker,controller \
  -e KAFKA_LISTENERS="PLAINTEXT://:9092,CONTROLLER://:9093,SSLHOST://:${HOST_PORT},SASLSSLHOST://:${SASL_SSL_PORT},MTLSHOST://:${MTLS_PORT}" \
  -e KAFKA_ADVERTISED_LISTENERS="PLAINTEXT://kafka:9092,SSLHOST://localhost:${HOST_PORT},SASLSSLHOST://localhost:${SASL_SSL_PORT},MTLSHOST://localhost:${MTLS_PORT}" \
  -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP="CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,SSLHOST:SSL,SASLSSLHOST:SASL_SSL,MTLSHOST:SSL" \
  -e KAFKA_INTER_BROKER_LISTENER_NAME=PLAINTEXT \
  -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@kafka:9093 \
  -e KAFKA_LISTENER_NAME_SASLSSLHOST_SASL_ENABLED_MECHANISMS=SCRAM-SHA-256 \
  -e KAFKA_SSL_KEYSTORE_LOCATION=/etc/kafka/secrets/broker.p12 \
  -e KAFKA_SSL_KEYSTORE_TYPE=PKCS12 \
  -e KAFKA_SSL_KEYSTORE_PASSWORD="$BROKER_PASS" \
  -e KAFKA_SSL_KEY_PASSWORD="$BROKER_PASS" \
  -e KAFKA_SSL_CLIENT_AUTH=none \
  -e KAFKA_LISTENER_NAME_MTLSHOST_SSL_CLIENT_AUTH=required \
  -e KAFKA_LISTENER_NAME_MTLSHOST_SSL_TRUSTSTORE_LOCATION=/etc/kafka/secrets/broker-truststore.p12 \
  -e KAFKA_LISTENER_NAME_MTLSHOST_SSL_TRUSTSTORE_TYPE=PKCS12 \
  -e KAFKA_LISTENER_NAME_MTLSHOST_SSL_TRUSTSTORE_PASSWORD="$TRUST_PASS" \
  -e KAFKA_OPTS="-Djava.security.auth.login.config=/etc/kafka/secrets/jaas.conf" \
  -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 \
  -e KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0 \
  -e KAFKA_AUTO_CREATE_TOPICS_ENABLE=true \
  "$IMAGE" >/dev/null

echo "容器 $NAME 已起，等待 SSL(${HOST_PORT}) + mTLS(${MTLS_PORT}) listener 就绪..."
for i in $(seq 1 60); do
  if docker exec "$NAME" bash -c 'exec 3<>/dev/tcp/127.0.0.1/'"${HOST_PORT}"'' 2>/dev/null \
     && docker exec "$NAME" bash -c 'exec 3<>/dev/tcp/127.0.0.1/'"${MTLS_PORT}"'' 2>/dev/null; then
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

# mTLS listener 必须真在听：listener 级 ssl.* 属性没注入成功时 broker 照常起、
# 只是该 listener 不存在——这里 fail-loud 免得测试侧只看到「不可达 → SKIP」
if ! docker exec "$NAME" bash -c 'exec 3<>/dev/tcp/127.0.0.1/'"${MTLS_PORT}"'' 2>/dev/null; then
  echo "FAIL: mTLS listener ${MTLS_PORT} 未就绪，broker 日志尾部："
  docker logs --tail 30 "$NAME" 2>&1 | tail -30
  exit 1
fi

# 握手自检：带 CA 应 Verify return code: 0（失败就别跑测试了）
echo "SSL 握手自检（带 CA）："
openssl s_client -connect "localhost:${HOST_PORT}" -CAfile "$OUT_DIR/ca.crt" </dev/null 2>&1 \
  | grep -E 'Verify return code|subject=' || true

# mTLS 正向自检：带客户端证书应握手成功并出示客户端证书（CN=e2e-client）
echo "mTLS 握手自检（带客户端证书）："
openssl s_client -connect "localhost:${MTLS_PORT}" -CAfile "$OUT_DIR/ca.crt" \
  -cert "$OUT_DIR/client.crt" -key "$OUT_DIR/client.key" </dev/null 2>&1 \
  | grep -E 'Verify return code|subject=|No client certificate' || true
# mTLS 负向自检：不带客户端证书。**TLS1.3 的 CertificateRequest 拒绝发生在握手之后**，
# 客户端侧 openssl 依旧打印 `Verify return code: 0`（那只是服务端链的校验结果），
# 所以判据必须看 broker 日志（实测：Failed authentication ... (SSL handshake failed)）
echo "mTLS 负向自检（不带客户端证书，判据 = broker 日志）："
timeout 10 openssl s_client -connect "localhost:${MTLS_PORT}" -CAfile "$OUT_DIR/ca.crt" \
  </dev/null >/dev/null 2>&1 || true
sleep 2
docker logs --since 20s "$NAME" 2>&1 \
  | grep -E "Failed authentication.*SSL handshake failed" | head -2 \
  || echo "  未在 broker 日志见到拒绝记录——listener 级 ssl.client.auth 可能没生效，别跑 mTLS 臂"

cat <<EOF

ready:
  SSL       bootstrap = localhost:${HOST_PORT}
  SASL_SSL  bootstrap = localhost:${SASL_SSL_PORT}  (SCRAM-SHA-256, user=${SASL_USER})
  MTLS      bootstrap = localhost:${MTLS_PORT}  (ssl.client.auth=required)
  truststores in ${OUT_DIR}: client-truststore.jks / .p12 / client-truststore-wrong.p12
  mTLS client keystore: ${OUT_DIR}/client-keystore.p12 (pass=${CLIENT_PASS:-client-secret})
EOF
