# 性能与压测指南

目标：验证网关 /v1/batch 在不同批量大小与速率下的吞吐、时延与错误率；评估下游（Kafka/CH/Flink）压测时的稳定性。

准备
- 本地：`infra/docker-compose.yml` 启动 Kafka/Apicurio/ClickHouse；`scripts/quickstart.sh` 启动网关并注入样例 Key
- k6：
  - 可使用 Docker 运行：
    ```bash
    docker run --rm -i grafana/k6 run - < scripts/loadtest/k6-pit.js \
      -e ENDPOINT=http://host.docker.internal:8080 \
      -e API_KEY=pk_test_example -e PROJECT_ID=p1 \
      -e RATE=200 -e DURATION=60s -e BATCH=50
    ```

建议场景
- 固定到达率：`RATE=200`、`BATCH=50`（10k evts/s）→ 验证网关 P95/P99 与 429 率
- 阶梯加压：30s 每档递增 RATE（200→400→800）观察转折点（CPU/GC/内存/429）
- gzip 影响：默认 k6 不压缩请求体；可通过反向代理或自定义脚本测试 gzip 节省带宽（实际生产建议开启 gzip）

指标观测（Prometheus/Grafana）
- 网关：HTTP 时延/错误、429 率、JVM/堆、GC
- Kafka：Producer 发送速率与错误；Consumer Lag（作业压测时）
- Flink：Checkpoint 成功率与耗时、BackPressure、Busy 时间
- ClickHouse：Insert 速率/错误、Parts/Merge 积压、查询延时

参数建议（起步）
- 批量：客户端 50～100；服务端批入 Kafka linger.ms=5–20ms、batch.size 64–256KB
- 并发：常见 10–20k evts/s 单副本可达；需按机器与网络调优
- 限速策略：按项目设置 rpm/ipRpm，防止恶意或异常流量冲击

结果记录
- 压测时记录 RATE/BATCH、响应 P95/P99、2xx/4xx/429 比例、CPU/内存占用
- 发现 429/5xx 增多时，降低 RATE 或增加副本；同时检查下游 Lag/Backpressure
