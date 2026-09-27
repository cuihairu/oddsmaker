package io.oddsmaker.common.otel;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OtelInit#init(String)} 的真实行为契约。
 *
 * <p><b>本模块首轮测试（此前 0 覆盖、无任何调用方，因此缺陷从未暴露）</b>：原实现在
 * {@code buildAndRegisterGlobal()} 之后又显式 {@code GlobalOpenTelemetry.set(otel)}——全局只能
 * 注册一次，第二次 set 必抛 {@code IllegalStateException}，故 {@code init()} 在被修正前
 * <b>百分之百抛错、从未返回</b>（实测异常消息见
 * {@link #secondInitFailsFastAndKeepsFirstRegistration()}）。
 * 修正 = 删掉那行冗余 set（注册由 buildAndRegisterGlobal 完成，语义不变），未做任何其它改动。
 * {@link #redundantExplicitSetWouldThrow()} 把「显式二次 set 必抛」钉成契约，防止那行被加回来。
 *
 * <p><b>已知缺口（按现状记录，未为凑绿改产线代码）</b>：入参 {@code serviceName} 目前**完全不参与
 * SDK 构造**——provider 用的是 {@code Resource.getDefault()}，其 {@code service.name} 恒为
 * {@code unknown_service:java}，多服务共用采集端时无法区分来源。证据见
 * {@link #initReturnsSdkAndBindsGlobal()}（传 null 也不 NPE、行为完全一致）。真正接进 resource
 * 需要 {@code Resource.to(Resource.create(...))} 合并，属产品决策，留待有调用方时再改。
 *
 * <p>全局单例是 JVM 级状态，三个用例按 {@code @Order} 顺序跑在同一 JVM 内（首个用例负责注册）。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OtelInitTest {

    @Test
    @Order(1)
    @DisplayName("init：返回非空 SDK 且全局已绑定（真实 tracer 产 recording span；serviceName 传 null 也照跑 = 该参数当前未被使用）")
    void initReturnsSdkAndBindsGlobal() {
        OpenTelemetry otel = OtelInit.init(null);

        assertNotNull(otel);
        assertTrue(otel instanceof OpenTelemetrySdk, "实际类型: " + otel.getClass().getName());
        // recording span 是「SDK 真在场」的判据：noop tracer 只会给 Span.getInvalid()（isRecording=false）
        assertTrue(otel.getTracer("probe").spanBuilder("returned").startSpan().isRecording(),
                "返回实例的 tracer 不产 recording span，说明没装进 SDK");

        // 全局绑定：buildAndRegisterGlobal 注册的是 ObfuscatedOpenTelemetry 包装，故不比引用而比行为
        OpenTelemetry global = GlobalOpenTelemetry.get();
        assertNotNull(global);
        assertTrue(global.getTracer("probe").spanBuilder("global").startSpan().isRecording(),
                "GlobalOpenTelemetry 未绑定到 SDK（仍是 noop）");
        assertTrue(global.getPropagators().getTextMapPropagator() != null);
    }

    @Test
    @Order(2)
    @DisplayName("重复 init：第二次调用 fail-fast 抛 IllegalStateException，且首次注册不被替换")
    void secondInitFailsFastAndKeepsFirstRegistration() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> OtelInit.init("late-comer"));
        assertTrue(ex.getMessage().contains("already been called"), ex.getMessage());

        // 抛错只影响第二次调用：全局仍指向首个 SDK（未回落 noop、未被覆盖）
        assertTrue(GlobalOpenTelemetry.get().getTracer("probe").spanBuilder("still-first").startSpan()
                .isRecording(), "二次调用后全局绑定被破坏");
    }

    @Test
    @Order(3)
    @DisplayName("回归防护：全局注册过一次后，再显式 GlobalOpenTelemetry.set 必抛（原实现多出的那行为何致命）")
    void redundantExplicitSetWouldThrow() {
        OpenTelemetry another = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder().build())
                .build();   // 用 build()（不注册全局），只测「再 set 一次」的后果
        assertThrows(IllegalStateException.class, () -> GlobalOpenTelemetry.set(another));
    }
}
