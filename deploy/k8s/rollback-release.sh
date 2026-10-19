#!/usr/bin/env bash
# DEPLOY-001 自动回滚（2026-10-19 审计整改，验收项「回滚步骤经过验证」的机制面）。
# release.yaml 的 Deploy/Verify 步以 trap ... ERR 调用本脚本；发布前两服务正在运行的
# 镜像由 "Record running images" 步写入 GITHUB_ENV（prev_control / prev_gateway）。
#
# 设计要点：
#   - 刻意不用 set -e：一个服务回滚失败不得中止另一个服务的回退（半回滚比整体失败更糟）。
#   - prev 镜像为空 = 首次发布没有旧版本可退，跳过并 ::warning::，不得对空值 set image。
#   - 任一服务回滚失败以非零退出，让 Deploy job 红灯（release needs: deploy，失败不带绿 Release）。
#
# 用法: rollback-release.sh <prev_control_image> <prev_gateway_image>

set -uo pipefail

NS="${NS:-oddsmaker}"

if [ "$#" -ne 2 ]; then
    echo "usage: $0 <prev_control_image> <prev_gateway_image>" >&2
    exit 64
fi

rollback_one() {
    local deployment="$1"
    local container="$2"
    local image="$3"

    if [ -z "$image" ]; then
        echo "::warning::no previous image recorded for ${deployment} — first release, nothing to roll back to"
        return 0
    fi

    echo "Rolling ${deployment} back to ${image}"
    kubectl set image "deployment/${deployment}" "${container}=${image}" -n "$NS" || {
        echo "::error::rollback failed for ${deployment}: kubectl set image returned non-zero"
        return 1
    }
    kubectl rollout status "deployment/${deployment}" -n "$NS" --timeout=300s || {
        echo "::error::rollback incomplete for ${deployment}: rollout did not converge within 300s"
        return 1
    }
    echo "Rolled ${deployment} back to ${image}"
    return 0
}

failed=0
rollback_one oddsmaker-control control-service "$1" || failed=1
rollback_one oddsmaker-gateway gateway-service "$2" || failed=1

if [ "$failed" -ne 0 ]; then
    echo "::error::automatic rollback did not complete for every service — manual intervention required"
fi

exit "$failed"
