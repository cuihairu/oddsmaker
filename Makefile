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

test:
	$(GRADLE) :services:gateway-service:test

perf-matrix:
	bash scripts/perf-matrix.sh

perf-report:
	python3 scripts/perf-report.py var/perf > var/perf/report.csv || true
