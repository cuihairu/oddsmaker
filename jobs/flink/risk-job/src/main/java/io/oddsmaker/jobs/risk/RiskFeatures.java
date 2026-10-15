package io.oddsmaker.jobs.risk;

import java.util.List;

/**
 * 显式特征清单（契约行 74「ruleConditions 引 EventSchema 字段」已代决落地，2026-10-16）——
 * risk-job 侧真源：本清单是「信号 × 窗口」派生特征的注册表，
 * {@code RiskJob.buildFeatureBranch} 与 {@code FeatureCalc} 各特征类的 feature 名一律引用本清单常量
 * （单一真源，防特征名漂移）。
 *
 * <p>特征名 ≠ 事件契约（EventSchema）字段——FEATURE 规则条件的合法取值域就是本清单，
 * control-service 侧写路径校验据此收紧（未知名规则在 risk_features 表永远查不到、
 * 生产侧永远不命中，此前只能静默跳过）。control-service 侧
 * {@code io.oddsmaker.control.service.RiskFeatures} 为同名镜像（两侧无共享依赖模块，
 * 由各自测试钉同一份文档名单对齐；两侧格式级改动必须同批上线语义）。
 * 信号侧原始字段清单见 {@link RiskSignals}。</p>
 */
final class RiskFeatures {

    /** 金币流入 1 小时滑窗（GoldGainFeature × 1h，FeatureCalc.goldGain） */
    static final String GOLD_GAIN_1H = "gold_gain_1h";

    /** 金币流入 24 小时滑窗（GoldGainFeature × 24h，FeatureCalc.goldGain） */
    static final String GOLD_GAIN_24H = "gold_gain_24h";

    /** 主体（仅 PLAYER）窗口内不同设备数（FeatureCalc.deviceCount） */
    static final String DEVICE_COUNT = "device_count";

    /** IP 口径：同一 client_ip 窗口内不同账号数（FeatureCalc.accountCount） */
    static final String ACCOUNT_COUNT_PER_IP = "account_count_per_ip";

    /** 主体窗口内 match 结果胜率 props.win=true 占比（FeatureCalc.winRate） */
    static final String WIN_RATE = "win_rate";

    /** 主体 10 分钟窗口事件数，FREQUENCY 型信号特征化（FeatureCalc.eventCount） */
    static final String EVENT_COUNT_10M = "event_count_10m";

    /** 全量名单（保序，buildFeatureBranch 注册序）：control-service 侧同名镜像以此为对账基线，两侧测试同钉 */
    static final List<String> ALL = List.of(
        GOLD_GAIN_1H, GOLD_GAIN_24H, DEVICE_COUNT,
        ACCOUNT_COUNT_PER_IP, WIN_RATE, EVENT_COUNT_10M);

    private RiskFeatures() {}
}
