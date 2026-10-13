.PHONY: help infra-up infra-down gateway control flink e2e superset-import test perf-matrix perf-report

GRADLE?=./gradlew

help:
	@echo "Targets: infra-up infra-down gateway control flink e2e superset-import test perf-matrix perf-report"

infra-up:
	cd infra && docker compose up -d kafka clickhouse apicurio otel-collector superset prometheus grafana

infra-down:
	cd infra && docker compose down

gateway:
	$(GRADLE) :services:gateway-service:bootRun

control:
	$(GRADLE) :services:control-service:bootRun

flink:
	bash scripts/run_flink.sh

e2e:
	bash scripts/e2e.sh

superset-import:
	bash scripts/superset-import.sh

# ================= 本地测试口径 (local testing scope) =================
# CI 与本地开发按改动面三路分流——改一行文档不该全量编译：
#   1) 文档改动（*.md、docs/**、图片）→ 不编译。主 CI workflow 的 paths-ignore
#      已跳过这些路径；本地无需全量跑。
#   2) 代码改动 → 只跑改动子集：
#      - `./gradlew :<改动 module>:test`
#      - `pytest ml/<改动目录>`
#   3) 全量（make test）→ 仅以下情形触发：
#      build.gradle / settings.gradle / go.mod / pyproject.toml、.github/**、Docker/Compose 配置、发布前。
# ==========================================================================

test:
	$(GRADLE) :services:gateway-service:test

perf-matrix:
	bash scripts/perf-matrix.sh

perf-report:
	python3 scripts/perf-report.py var/perf > var/perf/report.csv || true
