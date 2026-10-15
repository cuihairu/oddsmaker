package io.oddsmaker.control.service;

import java.util.List;

/**
 * 显式特征清单（契约行 74「ruleConditions 引 EventSchema 字段」已代决落地，2026-10-16）——
 * control-service 侧镜像，与 jobs/flink/risk-job {@code io.oddsmaker.jobs.risk.RiskFeatures} 同一份名单。
 *
 * <p>代决理由（见 docs/决策记录.md，用户可推翻）：FEATURE 规则条件的 feature 名引用的是
 * 「信号 × 窗口」派生特征（如 gold_gain_1h），不是事件契约（EventSchema）的原始字段，
 * 故合法取值域 = risk-job 计算面注册的特征集合（B5 首批 6 特征），而非 EventSchema 交叉校验。
 * 写路径以本清单收紧（未知名显式拒绝）；两侧无共享依赖模块，靠各自测试钉同一份文档名单对齐，
 * 改名单必须两侧同批（代决硬口径：两侧格式级改动必须同批上线语义）。</p>
 */
public final class RiskFeatures {

    /** 金币流入 1 小时窗（GoldGainFeature × 1h 滑窗） */
    public static final String GOLD_GAIN_1H = "gold_gain_1h";

    /** 金币流入 24 小时窗（GoldGainFeature × 24h 滑窗） */
    public static final String GOLD_GAIN_24H = "gold_gain_24h";

    /** 主体（仅 PLAYER）窗口内不同设备数 */
    public static final String DEVICE_COUNT = "device_count";

    /** IP 口径：同一 client_ip 窗口内出现的不同账号数 */
    public static final String ACCOUNT_COUNT_PER_IP = "account_count_per_ip";

    /** 主体窗口内 match 结果胜率（props.win=true 占比） */
    public static final String WIN_RATE = "win_rate";

    /** 主体 10 分钟窗口事件数（FREQUENCY 型信号特征化） */
    public static final String EVENT_COUNT_10M = "event_count_10m";

    /** 全量名单（保序，RiskJob.buildFeatureBranch 注册序）：与 risk-job 侧 RiskFeatures.ALL 同一份文档基线，两侧测试同钉 */
    public static final List<String> ALL = List.of(
        GOLD_GAIN_1H, GOLD_GAIN_24H, DEVICE_COUNT,
        ACCOUNT_COUNT_PER_IP, WIN_RATE, EVENT_COUNT_10M);

    private RiskFeatures() {}
}
