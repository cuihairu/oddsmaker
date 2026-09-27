package io.oddsmaker.common.otel;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;

public class OtelInit {
    /**
     * 构建 SDK 并注册为全局（{@code GlobalOpenTelemetry.get()} 即得同一实例）。
     *
     * <p>注意：{@code buildAndRegisterGlobal()} 内部已调用 {@code GlobalOpenTelemetry.set(...)}，
     * 而全局只能设置一次——再显式 set 会抛 {@code IllegalStateException}（实测：本模块补上
     * 首轮覆盖率测试才暴露——此前 0 覆盖且无调用方，此方法在该行修正前百分之百抛错、从未返回）。注册只做一次即可。
     *
     * <p>已知缺口（未改，按现状记账）：{@code serviceName} 目前不进 resource——构造用的是
     * {@code Resource.getDefault()}（service.name = {@code unknown_service:java}），
     * 多服务共用采集端时无法区分来源。证据见 OtelInitTest#initReturnsSdkAndBindsGlobal（传 null 也照跑）。
     */
    public static OpenTelemetry init(String serviceName) {
        SdkTracerProvider tp = SdkTracerProvider.builder()
                .setResource(Resource.getDefault())
                .build();
        return OpenTelemetrySdk.builder().setTracerProvider(tp).buildAndRegisterGlobal();
    }
}
