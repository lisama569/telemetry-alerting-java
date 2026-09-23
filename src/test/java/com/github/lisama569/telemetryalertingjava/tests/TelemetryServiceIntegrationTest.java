package com.github.lisama569.telemetryalertingjava.tests;

import com.github.lisama569.telemetryalertingjava.alerting.AlertRule;
import com.github.lisama569.telemetryalertingjava.alerting.AlertState;
import com.github.lisama569.telemetryalertingjava.alerting.ComparisonOperator;
import com.github.lisama569.telemetryalertingjava.alerting.EvaluationResult;
import com.github.lisama569.telemetryalertingjava.common.TimeSource;
import com.github.lisama569.telemetryalertingjava.model.CallRecordPoint;
import com.github.lisama569.telemetryalertingjava.model.CallStatus;
import com.github.lisama569.telemetryalertingjava.model.MetricSnapshot;
import com.github.lisama569.telemetryalertingjava.model.MetricType;
import com.github.lisama569.telemetryalertingjava.service.TelemetryService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 端到端：Spring 容器装配门面服务，注入可控时钟，完成记录→窗口聚合→告警评估。 */
@SpringBootTest
class TelemetryServiceIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(TelemetryServiceIntegrationTest.class);
    private static final String EP = "/api/intg";

    /** 用可控时钟覆盖默认系统时钟，保证集成测试结果确定、可复现。 */
    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        public TimeSource fixedTimeSource() {
            return new MutableTimeSource(10_000_000L);
        }
    }

    @Autowired
    private TelemetryService telemetryService;

    @Autowired
    private TimeSource timeSource;

    @Test
    void endToEndRecordSnapshotEvaluate() {
        assertNotNull(telemetryService);
        MutableTimeSource clock = (MutableTimeSource) timeSource;

        telemetryService.registerRule(new AlertRule("intg-rule", EP,
                MetricType.FAILURE_RATE, ComparisonOperator.GREATER_THAN_OR_EQUAL, 0.5d, 1, 1));

        // 3 次失败 + 1 次成功，failureRate = 0.75
        long ts = clock.nowMillis() - 100L;
        telemetryService.record(new CallRecordPoint(EP, ts, 5L, CallStatus.FAILURE));
        telemetryService.record(new CallRecordPoint(EP, ts, 15L, CallStatus.FAILURE));
        telemetryService.record(new CallRecordPoint(EP, ts, 25L, CallStatus.FAILURE));
        telemetryService.record(new CallRecordPoint(EP, ts, 35L, CallStatus.SUCCESS));

        MetricSnapshot snapshot = telemetryService.snapshot(EP, 30_000L);
        log.info("[集成] 输入: 4 次调用（3 失败/1 成功），聚合: total={}, success={}, failure={}, failureRate={}, avg={}, p95={}",
                snapshot.totalCount(), snapshot.successCount(), snapshot.failureCount(),
                snapshot.failureRate(), snapshot.avgLatencyMillis(), snapshot.p95LatencyMillis());
        assertEquals(4L, snapshot.totalCount());
        assertEquals(0.75d, snapshot.failureRate(), 0.0001d);

        EvaluationResult result = telemetryService.evaluate("intg-rule", 30_000L);
        log.info("[集成] 状态判定: {} => {}，依据: {}", result.metricValue(), result.state(), result.reason());
        assertEquals(AlertState.FIRING, result.state(), "pendingStreak=1 且 failureRate=0.75>=0.5 应触发");

        // 时钟滚过窗口后，指标归零，再次评估应恢复
        clock.advance(60_000L);
        MetricSnapshot expired = telemetryService.snapshot(EP, 30_000L);
        assertEquals(0L, expired.totalCount());
        EvaluationResult recovered = telemetryService.evaluate("intg-rule", 30_000L);
        log.info("[集成] 窗口滚动后 total={}, 状态={}, 依据: {}", expired.totalCount(),
                recovered.state(), recovered.reason());
        assertEquals(AlertState.RESOLVED, recovered.state());
    }
}
