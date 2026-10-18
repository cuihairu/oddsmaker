package io.oddsmaker.control.service;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 游戏分析就绪度（审计 DATA-002 就绪状态的统一读口）。
 * available 只反映「CH 可达」；analysisLastEventAt 反映采集→分析链路最近一次落数
 * （events.max(ts_server)，ISO-8601 UTC；null=链路从未产出或查询失败）。
 * 采集/风控独立状态暂缺真实信号，不在此编造：events 即端到端产物（Standard 档无
 * Flink 时恒 null）；risk_events 以规则命中为前提（流量条件性），不能当链路存活信号。
 * 待真实信号（如 Kafka lag 导出）接入后在出参叠加；不发明新鲜度阈值，消费方自行判读。
 */
@Service
public class GameReadinessService {

    private final ClickHouseClient client;

    public GameReadinessService(ClickHouseClient client) {
        this.client = client;
    }

    public Map<String, Object> readiness(String gameId, String environment) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("gameId", gameId);
        resp.put("environment", environment);
        boolean ch = client.isAvailable();
        resp.put("available", ch);
        resp.put("analysisLastEventAt", ch ? client.analysisLastEventAt(gameId, environment) : null);
        return resp;
    }
}
