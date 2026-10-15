package io.oddsmaker.jobs.risk;

import java.util.List;

/**
 * 显式信号清单（重构契约 §2.4「2.0 动作」落地，2026-10-15 代决）：风控域从事件流提取的原始信号名单。
 * 信号 = 网关校验后事件流的原始字段口径（不落新表、不产指标）；特征 = 信号 × 窗口的派生
 * （B5 {@code FeatureCalc}：gold_gain_1h/24h、device_count、account_count_per_ip、win_rate、event_count_10m）。
 *
 * <p>本类只做显式声明与对账锚点——各信号的提取逻辑与序列化格式维持原处（{@code RiskJob}/{@code FeatureCalc}），
 * 不改任何既有暴露面；命名沿用仓内 snake_case 惯例。control-service 侧
 * {@code io.oddsmaker.control.service.RiskSignals} 为同名镜像（两侧无共享依赖模块，
 * 由各自测试钉同一份文档名单对齐；两侧格式级改动必须同批上线语义）。</p>
 */
final class RiskSignals {

    /** 金币流入：flow_type=source 且 resource_id=gold 的 amount 合计（FeatureCalc.isGoldSource/goldGain） */
    static final String GOLD_GAIN = "gold_gain";

    /** 事件 IP：client_ip，IP 口径 scope 源（FeatureCalc.ipScopeKey → account_count_per_ip） */
    static final String LOGIN_IP = "login_ip";

    /** 设备：device_id，设备聚集口径（FeatureCalc.deviceCount → device_count） */
    static final String DEVICE = "device";

    /** 对局胜负：match:complete/:end 事件 props.win 布尔（FeatureCalc.isMatchResult/extractWin → win_rate） */
    static final String WIN_FLAG = "win_flag";

    /** 事件频次：窗口内事件计数（FeatureCalc.eventCount → event_count_10m，FREQUENCY 型信号特征化） */
    static final String EVENT_FREQUENCY = "event_frequency";

    /** 收据重复：receipt_hash 退化 order_id 的窗口内重复提交（RiskJob receiptKey → duplicate receipt 规则） */
    static final String RECEIPT = "receipt";

    /** 激励广告：ad_format=rewarded 的 reward 事件（RiskJob.isAdReward） */
    static final String AD_REWARD = "ad_reward";

    /** 付费金额：revenue_amount 窗口合计（RiskJob revenue 聚合） */
    static final String REVENUE = "revenue";

    /** 信任档位：trust_level（LOW/HIGH/COMPUTED），BLOCK 级动作门槛输入（RiskJob.gateAction / allHigh） */
    static final String TRUST_LEVEL = "trust_level";

    /** 全量名单（保序）：control-service 侧同名镜像以此为对账基线，两侧测试同钉 */
    static final List<String> ALL = List.of(
        GOLD_GAIN, LOGIN_IP, DEVICE, WIN_FLAG, EVENT_FREQUENCY,
        RECEIPT, AD_REWARD, REVENUE, TRUST_LEVEL);

    private RiskSignals() {}
}
