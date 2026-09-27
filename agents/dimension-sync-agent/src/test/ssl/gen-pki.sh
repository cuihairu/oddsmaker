#!/usr/bin/env bash
# 为 KafkaSourceSslBrokerE2ETest 生成本地一次性 PKI：自签 CA + broker 证书 + 客户端信任库。
#
# 产物全部落在 OUT_DIR（默认 /tmp/oddsmaker-kafka-ssl-e2e），**私钥不进仓库**：
# ca.key / broker.key 是私钥，仓库里只保留这段生成配方 + 测试代码里的路径约定。
# 证书 30 天有效，过期重跑本脚本 + 重起 broker 即可。
#
# 产物：
#   ca.crt                        自签 CA（公证证书，可公开）
#   rogue-ca.crt                  无关的第二张 CA（负向臂：不可信信任链）
#   broker.crt / broker.key       broker 证书与私钥（SAN=DNS:localhost,IP:127.0.0.1）
#   secrets/broker.p12            broker keystore（PKCS12，容器挂载到 /etc/kafka/secrets）
#   secrets/jaas.conf             SASL_SSL listener 用的 KafkaServer JAAS
#   client-truststore.jks         客户端信任库（CA，JKS = kafka-clients 默认 type）
#   client-truststore.p12         客户端信任库（CA，PKCS12，走显式 type 键）
#   client-truststore-wrong.p12   客户端信任库（rogue CA，PKCS12，负向臂）
#
# 密码（与测试代码默认值一致，改这里也要改测试的 System property 默认值）：
#   BROKER_PASS=broker-secret  TRUST_PASS=trust-secret
#
# 用法：
#   ./gen-pki.sh
#   OUT_DIR=/path ./gen-pki.sh
set -euo pipefail

OUT_DIR="${OUT_DIR:-/tmp/oddsmaker-kafka-ssl-e2e}"
BROKER_PASS="${BROKER_PASS:-broker-secret}"
TRUST_PASS="${TRUST_PASS:-trust-secret}"
DAYS="${DAYS:-30}"

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR/secrets"
cd "$OUT_DIR"

# ── 自签 CA（CA:TRUE，才能签发 broker 证书）──
openssl req -x509 -newkey rsa:2048 -nodes -keyout ca.key -out ca.crt -days "$DAYS" \
  -subj "/CN=oddsmaker-kafka-e2e-ca" \
  -addext "basicConstraints=critical,CA:TRUE" \
  -addext "keyUsage=critical,keyCertSign,cRLSign" 2>/dev/null

# ── 无关的第二张 CA：只放进「错误信任库」，用于负向臂 ──
openssl req -x509 -newkey rsa:2048 -nodes -keyout rogue-ca.key -out rogue-ca.crt -days "$DAYS" \
  -subj "/CN=oddsmaker-kafka-e2e-rogue-ca" \
  -addext "basicConstraints=critical,CA:TRUE" 2>/dev/null

# ── broker 证书：SAN 必须覆盖 localhost。kafka-clients 客户端默认
#    ssl.endpoint.identification.algorithm=https（实测 kafka-clients 3.7/3.8 常量），
#    主机名校验默认开启，CN-only 证书会被判 PKIX 失败 ──
openssl req -new -newkey rsa:2048 -nodes -keyout broker.key -out broker.csr \
  -subj "/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" 2>/dev/null
openssl x509 -req -in broker.csr -CA ca.crt -CAkey ca.key -CAcreateserial -days "$DAYS" \
  -copy_extensions copyall -out broker.crt 2>/dev/null
# keystore 内含「叶证书 + CA」，broker 出示完整链
openssl pkcs12 -export -inkey broker.key -in broker.crt -certfile ca.crt -name broker \
  -out secrets/broker.p12 -passout "pass:$BROKER_PASS"

# ── 客户端信任库：一律 keytool -importcert 生成（只导入公钥证书，无私钥）。
#    实测坑：openssl 3.5 `pkcs12 -export -nokeys` 的纯证书 PKCS12，JDK KeyStore
#    加载成功但读出 0 条目（kafka-clients 信任锚为空，握手全挂且报错形态千奇百怪，
#    从 listNodes 超时到 TLS CertificateVerify 失败都见得到）；带私钥的 broker
#    keystore 不受影响。keytool 写出的才是 JDK 保证可读的规范格式 ──
keytool -J-Djava.security.egd=file:/dev/urandom -importcert -noprompt -alias ca -file ca.crt \
  -keystore client-truststore.p12 -storetype PKCS12 -storepass "$TRUST_PASS" >/dev/null
keytool -J-Djava.security.egd=file:/dev/urandom -importcert -noprompt -alias rogue-ca -file rogue-ca.crt \
  -keystore client-truststore-wrong.p12 -storetype PKCS12 -storepass "$TRUST_PASS" >/dev/null
keytool -J-Djava.security.egd=file:/dev/urandom -importcert -noprompt -alias oddsmaker-e2e-ca -file ca.crt \
  -keystore client-truststore.jks -storetype JKS -storepass "$TRUST_PASS" >/dev/null

# ── SASL_SSL listener 用的 broker 侧 JAAS（客户端凭证由测试代码注入，
#    broker 只需要一个 KafkaServer 条目供 SCRAM 内部使用）──
cat > secrets/jaas.conf <<'EOF'
KafkaServer {
  org.apache.kafka.common.security.scram.ScramLoginModule required
  username="e2e-broker"
  password="e2e-broker-secret";
};
EOF

echo "PKI 已生成于 $OUT_DIR"
openssl verify -CAfile ca.crt broker.crt
openssl x509 -in broker.crt -noout -subject -ext subjectAltName
