package com.github.lisama569.telemetryalertingjava.tests;

import com.github.lisama569.telemetryalertingjava.core.FixedClock;
import com.github.lisama569.telemetryalertingjava.metrics.CallStatus;
import com.github.lisama569.telemetryalertingjava.metrics.InvalidMetricException;
import com.github.lisama569.telemetryalertingjava.metrics.MetricsRegistry;
import com.github.lisama569.telemetryalertingjava.metrics.WindowSnapshot;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指标口径、窗口滚动与非法埋点拒绝测试。
 */
class MetricsRegistryTest {

    private static final Logger log = LoggerFactory.getLogger(MetricsRegistryTest.class);

    private MetricsRegistry newRegistry(FixedClock clock) {
        // 10s 窗口，1s 桶，便于用时钟推进模拟滚动
        return new MetricsRegistry(10_000L, 1_000L, clock);
    }

    private void logSnapshot(String title, WindowSnapshot s) {
        log.info("{} | api={} window=[{},{}) total={} success={} failure={} successRate={} "
                        + "failureRate={} avgLatency={} maxLatency={} p50={} p95={} p99={}",
                title, s.api(), s.windowStartMillis(), s.windowEndMillis(),
                s.callCount(), s.successCount(), s.failureCount(),
                String.format("%.3f", s.successRate()), String.format("%.3f", s.failureRate()),
                String.format("%.2f", s.avgLatencyMillis()), s.maxLatencyMillis(),
                s.latencyPercentile(50), s.latencyPercentile(95), s.latencyPercentile(99));
    }

    @Test
    void aggregatesCountsRatesLatenciesWithinWindow() {
        FixedClock clock = new FixedClock(100_000L);
        MetricsRegistry registry = newRegistry(clock);

        // 输入：8 成功（延迟 10~80ms），2 失败（延迟 90、100ms）
        for (int i = 1; i <= 8; i++) {
            registry.record("/order", CallStatus.SUCCESS, i * 10L, clock.nowMillis());
        }
        registry.record("/order", CallStatus.FAILURE, 90L, clock.nowMillis());
        registry.record("/order", CallStatus.FAILURE, 100L, clock.nowMillis());

        WindowSnapshot s = registry.snapshot("/order").orElseThrow();
        logSnapshot("聚合结果", s);

        assertAll("聚合口径",
                () -> assertEquals(10L, s.callCount(), "总调用次数"),
                () -> assertEquals(8L, s.successCount()),
                () -> assertEquals(2L, s.failureCount()),
                () -> assertEquals(0.8d, s.successRate(), 1e-9),
                () -> assertEquals(0.2d, s.failureRate(), 1e-9),
                () -> assertEquals(55d, s.avgLatencyMillis(), 1e-9, "(10+...+100)/10"),
                () -> assertEquals(100L, s.maxLatencyMillis()),
                () -> assertEquals(50L, s.latencyPercentile(50), "最近秩法 rank=ceil(0.5*10)=5 -> 第5小样本"),
                () -> assertEquals(100L, s.latencyPercentile(95)),
                () -> assertTrue(s.callCount() >= 0 && s.successCount() >= 0 && s.failureCount() >= 0,
                        "任何计数不得为负"));
    }

    @Test
    void rollsWindowAndEvictsExpiredBucketsWithoutNegativeCounts() {
        FixedClock clock = new FixedClock(0L);
        MetricsRegistry registry = newRegistry(clock);

        registry.record("/pay", CallStatus.SUCCESS, 10L, 0L);      // t=0
        clock.setMillis(5_000L);
        registry.record("/pay", CallStatus.FAILURE, 20L, 5_000L);  // t=5s
        clock.setMillis(9_999L);
        WindowSnapshot before = registry.snapshot("/pay").orElseThrow();
        logSnapshot("滚动前(t=9.999s)", before);
        assertEquals(2L, before.callCount());

        // 推进到 10.001s：t=0 所在的桶整体落在 [now-window, now) 之外，被淘汰
        clock.setMillis(11_001L);
        WindowSnapshot after = registry.snapshot("/pay").orElseThrow();
        logSnapshot("滚动后(t=11.001s)", after);
        assertAll("过期淘汰",
                () -> assertEquals(1L, after.callCount(), "t=0 的调用必须被淘汰"),
                () -> assertEquals(0L, after.successCount()),
                () -> assertEquals(1L, after.failureCount()),
                () -> assertTrue(after.successCount() >= 0 && after.failureCount() >= 0
                        && after.callCount() >= 0, "淘汰后不得出现负值"));

        // 再推进到 15.001s：t=5s 的桶也滚出窗口
        clock.setMillis(16_001L);
        Optional<WindowSnapshot> empty = registry.snapshot("/pay");
        log.info("滚动到 t=16.001s，窗口内快照存在? {}", empty.isPresent());
        assertTrue(empty.isEmpty(), "全部过期后不应返回含数据的快照");
    }

    @Test
    void rejectsInvalidEventsWithDistinguishableReasons() {
        FixedClock clock = new FixedClock(5_000L);
        MetricsRegistry registry = newRegistry(clock);

        assertRejected("空接口名", MetricsRegistry.REASON_BLANK_API,
                () -> registry.record("  ", CallStatus.SUCCESS, 1L, 5_000L));
        assertRejected("状态缺失", MetricsRegistry.REASON_NULL_STATUS,
                () -> registry.record("/a", null, 1L, 5_000L));
        assertRejected("负延迟", MetricsRegistry.REASON_NEGATIVE_LATENCY,
                () -> registry.record("/a", CallStatus.SUCCESS, -1L, 5_000L));
        assertRejected("延迟越界", MetricsRegistry.REASON_LATENCY_TOO_LARGE,
                () -> registry.record("/a", CallStatus.SUCCESS, 25L * 3600_000L, 5_000L));
        assertRejected("未来时间戳", MetricsRegistry.REASON_TIMESTAMP_IN_FUTURE,
                () -> registry.record("/a", CallStatus.SUCCESS, 1L, 5_001L));
        assertRejected("超出窗口的时间戳", MetricsRegistry.REASON_TIMESTAMP_TOO_OLD,
                () -> registry.record("/a", CallStatus.SUCCESS, 1L, -6_000L));
        assertRejected("事件对象为 null", MetricsRegistry.REASON_NULL_EVENT,
                () -> registry.record(null));

        // 全部拒绝后不得静默计入
        assertTrue(registry.snapshot("/a").isEmpty(), "非法埋点不得计入统计");
        log.info("非法埋点全部被拒绝，/a 未产生任何统计数据");
    }

    private void assertRejected(String title, String expectedReason, Runnable action) {
        InvalidMetricException ex = assertThrows(InvalidMetricException.class, action::run);
        assertEquals(expectedReason, ex.reason());
        log.info("[非法埋点拒绝] 场景={} 原因码={} 说明={}", title, ex.reason(), ex.getMessage());
    }

    @Test
    void emptyApiIsNotConfusedWithUnknown() {
        FixedClock clock = new FixedClock(0L);
        MetricsRegistry registry = newRegistry(clock);
        assertFalse(registry.snapshot("/never-called").isPresent());
        log.info("从未上报的接口 /never-called 快照为空（区别于非法入参，不抛异常）");
    }
}
