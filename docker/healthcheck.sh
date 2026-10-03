#!/usr/bin/env sh
# Oddsmaker 统一健康检查脚本
# 根据 SERVICE 环境变量选择检查 control(8085) 或 gateway(8080)

set -eu

SERVICE="${SERVICE:-control}"

case "$SERVICE" in
    control)
        PORT=8085
        ;;
    gateway)
        PORT=8080
        ;;
    *)
        echo "Unknown SERVICE: $SERVICE (expected control|gateway)" >&2
        exit 1
        ;;
esac

# 尝试 wget，失败则回退 curl
if wget --no-verbose --tries=1 -qO- "http://localhost:${PORT}/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
    exit 0
fi

if curl -fsS "http://localhost:${PORT}/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
    exit 0
fi

exit 1