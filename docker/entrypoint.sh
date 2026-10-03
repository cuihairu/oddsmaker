#!/bin/sh
set -e

SERVICE="${SERVICE:-control}"

case "$SERVICE" in
  control)
    export SERVER_PORT="${CONTROL_PORT}"
    export HEALTHCHECK_PORT="${CONTROL_PORT}"
    CONFIG_FILE="/app/config/control-application.yaml"
    JAR_FILE="/app/lib/control-service.jar"
    ;;
  gateway)
    export SERVER_PORT="${GATEWAY_PORT}"
    export HEALTHCHECK_PORT="${GATEWAY_PORT}"
    CONFIG_FILE="/app/config/gateway-application.yaml"
    JAR_FILE="/app/lib/gateway-service.jar"
    ;;
  *)
    echo "ERROR: Unknown SERVICE='$SERVICE'. Valid values: control, gateway" >&2
    exit 1
    ;;
esac

# Spring 配置加载优先级：默认 < 配置文件 < 环境变量 < 命令行
# additional-location 加 optional: 前缀——用户挂载空 /app/config 盖掉镜像内模板时也能正常启动
exec java ${JAVA_OPTS} -jar "${JAR_FILE}" \
     --spring.config.additional-location=optional:file:"${CONFIG_FILE}",optional:file:/app/config/