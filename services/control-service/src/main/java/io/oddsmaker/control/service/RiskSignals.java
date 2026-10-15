package io.oddsmaker.control.service;

import java.util.List;

/**
 * 显式信号清单（重构契约 §2.4「2.0 动作」落地，2026-10-15 代决）——control-service 侧镜像，
 * 与 jobs/flink/risk-job {@code io.oddsmaker.jobs.risk.RiskSignals} 同一份名单。
 *
 * <p>两侧无共享依赖模块，靠各自测试钉同一份文档名单对齐（改名单必须两侧同批，代决硬口径：
 * 两侧格式级改动必须同批上线语义）。本类只做显式声明与对账锚点，不改变任何既有暴露面——
 * 信号提取在 risk-job（FeatureCalc/RiskJob），控制面通过 FEATURE 规则条件引用其窗口派生特征
 * （feature = signal × window，如 gold_gain_1h），解析与校验见 {@code dto/RuleConditions}。</p>
 */
public final class RiskSignals {

    /** 金币流入：flow_type=source 且 resource_id=gold 的 amount 合计 */
    public static final String GOLD_GAIN = "gold_gain";

    /** 事件 IP：client_ip，IP 口径 scope 源 */
    public static final String LOGIN_IP = "login_ip";

    /** 设备：device_id，设备聚集口径 */
    public static final String DEVICE = "device";

    /** 对局胜负：match:complete/:end 事件 props.win 布尔 */
    public static final String WIN_FLAG = "win_flag";

    /** 事件频次：窗口内事件计数（FREQUENCY 型信号特征化） */
    public static final String EVENT_FREQUENCY = "event_frequency";

    /** 收据重复：receipt_hash 退化 order_id 的窗口内重复提交 */
    public static final String RECEIPT = "receipt";

    /** 激励广告：ad_format=rewarded 的 reward 事件 */
    public static final String AD_REWARD = "ad_reward";

    /** 付费金额：revenue_amount 窗口合计 */
    public static final String REVENUE = "revenue";

    /** 信任档位：trust_level，BLOCK 级动作门槛输入 */
    public static final String TRUST_LEVEL = "trust_level";

    /** 全量名单（保序）：与 risk-job 侧 RiskSignals.ALL 同一份文档基线，两侧测试同钉 */
    public static final List<String> ALL = List.of(
        GOLD_GAIN, LOGIN_IP, DEVICE, WIN_FLAG, EVENT_FREQUENCY,
        RECEIPT, AD_REWARD, REVENUE, TRUST_LEVEL);

    private RiskSignals() {}
}
