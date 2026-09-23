package com.github.lisama569.telemetryalertingjava.config;

import com.github.lisama569.telemetryalertingjava.alerting.AlertEvaluator;
import com.github.lisama569.telemetryalertingjava.alerting.DefaultAlertEvaluator;
import com.github.lisama569.telemetryalertingjava.collector.InMemoryMetricsCollector;
import com.github.lisama569.telemetryalertingjava.collector.MetricsCollector;
import com.github.lisama569.telemetryalertingjava.common.SystemTimeSource;
import com.github.lisama569.telemetryalertingjava.common.TimeSource;
import com.github.lisama569.telemetryalertingjava.service.DefaultTelemetryService;
import com.github.lisama569.telemetryalertingjava.service.TelemetryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 指标采集与告警的 Spring 装配。
 * 默认使用系统时钟；测试可注入自定义 {@link TimeSource} 覆盖，实现确定性时间控制。
 */
@Configuration
public class TelemetryAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TimeSource timeSource() {
        return new SystemTimeSource();
    }

    @Bean
    @ConditionalOnMissingBean
    public MetricsCollector metricsCollector(TimeSource timeSource) {
        return new InMemoryMetricsCollector(timeSource);
    }

    @Bean
    @ConditionalOnMissingBean
    public AlertEvaluator alertEvaluator(TimeSource timeSource) {
        return new DefaultAlertEvaluator(timeSource);
    }

    @Bean
    @ConditionalOnMissingBean
    public TelemetryService telemetryService(MetricsCollector metricsCollector,
                                             AlertEvaluator alertEvaluator) {
        return new DefaultTelemetryService(metricsCollector, alertEvaluator);
    }
}
