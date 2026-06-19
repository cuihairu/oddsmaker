package io.oddsmaker.common.otel;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;

public class OtelInit {
    public static OpenTelemetry init(String serviceName) {
        SdkTracerProvider tp = SdkTracerProvider.builder()
                .setResource(Resource.getDefault())
                .build();
        OpenTelemetry otel = OpenTelemetrySdk.builder().setTracerProvider(tp).buildAndRegisterGlobal();
        GlobalOpenTelemetry.set(otel);
        return otel;
    }
}
