package io.oddsmaker.jobs.risk;

import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.WindowedStream;
import org.apache.flink.streaming.api.windowing.assigners.SlidingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;

/**
 * RiskJob 管道辅助（拆分 LAY-001）：主体/收据分键滑动窗口与 B5 特征作业分支。
 * buildPipeline 经这些装配点接线，避免窗口分配与特征分支逻辑内联在作业类。
 */
final class RiskPipeline {

    private RiskPipeline() {}

    /** 按主体（玩家/设备）分键的滑动窗口。 */
    static WindowedStream<RiskJob.RiskInput, String, TimeWindow> windowedBySubject(DataStream<RiskJob.RiskInput> inputs, int windowMin) {
        return inputs
                .keyBy(RiskRules::subjectWindowKey)
                .window(SlidingEventTimeWindows.of(Time.minutes(windowMin), Time.minutes(RiskJobConfig.slideMinutes(windowMin))));
    }

    /** 按主体 + 收据键分键的滑动窗口（重复收据检测）。 */
    static WindowedStream<RiskJob.RiskInput, String, TimeWindow> windowedByReceipt(DataStream<RiskJob.RiskInput> inputs, int windowMin) {
        return inputs
                .keyBy(RiskRules::receiptWindowKey)
                .window(SlidingEventTimeWindows.of(Time.minutes(windowMin), Time.minutes(RiskJobConfig.slideMinutes(windowMin))));
    }

    /**
     * B5 特征作业分支：首批 6 特征的滑动窗口聚合（清单以计划书 §5.1 为准）。
     * 窗口步长：1h 特征 5 分钟一算（规则读值新鲜度），24h 半小时，10 分钟特征 1 分钟。
     * 分口径 keyBy：gold/device/win/event_count 走主体键，account_count_per_ip 走 IP 键。
     */
    static DataStream<FeatureRow> buildFeatureBranch(DataStream<RiskJob.RiskInput> inputs) {
        DataStream<FeatureRow> gold1h = inputs
                .filter(FeatureCalc::isGoldSource)
                .keyBy(RiskRules::subjectWindowKey)
                .window(SlidingEventTimeWindows.of(Time.hours(1), Time.minutes(5)))
                .process(new FeatureCalc.GoldGainFeature(RiskFeatures.GOLD_GAIN_1H))
                .returns(Types.POJO(FeatureRow.class));

        DataStream<FeatureRow> gold24h = inputs
                .filter(FeatureCalc::isGoldSource)
                .keyBy(RiskRules::subjectWindowKey)
                .window(SlidingEventTimeWindows.of(Time.hours(24), Time.minutes(30)))
                .process(new FeatureCalc.GoldGainFeature(RiskFeatures.GOLD_GAIN_24H))
                .returns(Types.POJO(FeatureRow.class));

        DataStream<FeatureRow> deviceCount = inputs
                .filter(RiskRules::isPlayerSubject)
                .keyBy(RiskRules::subjectWindowKey)
                .window(SlidingEventTimeWindows.of(Time.hours(1), Time.minutes(5)))
                .process(new FeatureCalc.DeviceCountFeature())
                .returns(Types.POJO(FeatureRow.class));

        DataStream<FeatureRow> accountPerIp = inputs
                .filter(RiskRules::hasIpAccount)
                .keyBy(RiskRules::ipWindowKey)
                .window(SlidingEventTimeWindows.of(Time.hours(1), Time.minutes(5)))
                .process(new FeatureCalc.AccountPerIpFeature())
                .returns(Types.POJO(FeatureRow.class));

        DataStream<FeatureRow> winRate = inputs
                .filter(FeatureCalc::isMatchResult)
                .keyBy(RiskRules::subjectWindowKey)
                .window(SlidingEventTimeWindows.of(Time.hours(1), Time.minutes(5)))
                .process(new FeatureCalc.WinRateFeature())
                .returns(Types.POJO(FeatureRow.class));

        DataStream<FeatureRow> eventCount = inputs
                .keyBy(RiskRules::subjectWindowKey)
                .window(SlidingEventTimeWindows.of(Time.minutes(10), Time.minutes(1)))
                .process(new FeatureCalc.EventCountFeature())
                .returns(Types.POJO(FeatureRow.class));

        return gold1h.union(gold24h).union(deviceCount).union(accountPerIp).union(winRate).union(eventCount);
    }
}
