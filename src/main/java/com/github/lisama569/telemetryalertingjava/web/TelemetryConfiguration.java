package com.github.lisama569.telemetryalertingjava.web;

import com.github.lisama569.telemetryalertingjava.alert.AlertEvaluator;
import com.github.lisama569.telemetryalertingjava.core.Clock;
import com.github.lisama569.telemetryalertingjava.core.SystemClock;
import com.github.lisama569.telemetryalertingjava.metrics.MetricsRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 进程内指标采集与告警评估的 Spring 装配。全部为内存态，不依赖任何外部服务。
 */
@Configuration
public class TelemetryConfiguration {

    @Bean
    public Clock telemetryClock() {
        return new SystemClock();
    }

    @Bean
    public MetricsRegistry metricsRegistry(
            Clock clock,
            @Value("${telemetry.window-millis:60000}") long windowMillis,
            @Value("${telemetry.bucket-millis:1000}") long bucketMillis,
            @Value("${telemetry.retention-millis:60000}") long retentionMillis,
            @Value("${telemetry.max-events-per-api:0}") long maxEventsPerApi) {
        return new MetricsRegistry(windowMillis, bucketMillis, retentionMillis,
                maxEventsPerApi, clock);
    }

    @Bean
    public AlertEvaluator alertEvaluator(MetricsRegistry registry, Clock clock) {
        return new AlertEvaluator(registry, clock);
    }
}
