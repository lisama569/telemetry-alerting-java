package com.github.lisama569.telemetryalertingjava.tests;

import com.github.lisama569.telemetryalertingjava.core.FixedClock;
import com.github.lisama569.telemetryalertingjava.metrics.CallEvent;
import com.github.lisama569.telemetryalertingjava.metrics.CallStatus;
import com.github.lisama569.telemetryalertingjava.metrics.InvalidMetricException;
import com.github.lisama569.telemetryalertingjava.metrics.MetricsRegistry;
import com.github.lisama569.telemetryalertingjava.metrics.WindowSnapshot;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 按时间点回看测试：结果只反映该时刻及所在窗口内的数据，不受之后写入影响，反复查询稳定。
 */
class SnapshotAtTest {

    private static final Logger log = LoggerFactory.getLogger(SnapshotAtTest.class);

    private MetricsRegistry newRegistry(FixedClock clock) {
        return new MetricsRegistry(10_000L, 1_000L, clock);
    }

    private void logSnapshot(String title, WindowSnapshot s) {
        log.info("{} | api={} window=[{},{}] total={} success={} failure={} avgLatency={} maxLatency={}",
                title, s.api(), s.windowStartMillis(), s.windowEndMillis(),
                s.callCount(), s.successCount(), s.failureCount(),
                String.format("%.2f", s.avgLatencyMillis()), s.maxLatencyMillis());
    }

    @Test
    void snapshotAtIsStableAndImmuneToLaterWrites() {
        FixedClock clock = new FixedClock(100_000L);
        MetricsRegistry registry = newRegistry(clock);

        // t=100s 写入 2 条（1 成功 1 失败）
        registry.record("/order", CallStatus.SUCCESS, 10L, 100_000L);
        registry.record("/order", CallStatus.FAILURE, 20L, 100_000L);
        // t=105s 写入 3 条
        clock.setMillis(105_000L);
        registry.record("/order", CallStatus.SUCCESS, 30L, 105_000L);
        registry.record("/order", CallStatus.SUCCESS, 40L, 105_000L);
        registry.record("/order", CallStatus.FAILURE, 50L, 105_000L);
        log.info("输入：t=100000 写 2 条（1成1败），t=105000 写 3 条（2成1败）");

        WindowSnapshot at100k = registry.snapshotAt("/order", 100_000L).orElseThrow();
        logSnapshot("回看 t=100000", at100k);
        assertAll("t=100000 只看得到当时的 2 条",
                () -> assertEquals(2L, at100k.callCount()),
                () -> assertEquals(1L, at100k.successCount()),
                () -> assertEquals(1L, at100k.failureCount()),
                () -> assertEquals(100_000L, at100k.windowEndMillis()),
                () -> assertEquals(90_000L, at100k.windowStartMillis()));

        // 之后继续写入更多数据，回看结果不得改变
        registry.record("/order", CallStatus.SUCCESS, 999L, 105_000L);
        registry.record("/order", CallStatus.FAILURE, 888L, 105_000L);
        log.info("追加输入：t=105000 再写 2 条（延迟 999/888）");
        WindowSnapshot again = registry.snapshotAt("/order", 100_000L).orElseThrow();
        WindowSnapshot third = registry.snapshotAt("/order", 100_000L).orElseThrow();
        logSnapshot("追加写入后回看 t=100000", again);
        assertAll("同一时刻反复查询稳定",
                () -> assertEquals(at100k, again, "之后的新写入不得影响历史回看"),
                () -> assertEquals(at100k, third, "反复查询结果必须一致"));

        WindowSnapshot at105k = registry.snapshotAt("/order", 105_000L).orElseThrow();
        logSnapshot("回看 t=105000", at105k);
        assertEquals(7L, at105k.callCount(), "t=105000 的窗口 [95000,105000] 含全部 7 条");
    }

    @Test
    void snapshotAtMatchesRealtimeSnapshotTakenAtSameMoment() {
        FixedClock clock = new FixedClock(100_000L);
        MetricsRegistry registry = newRegistry(clock);

        registry.record("/pay", CallStatus.SUCCESS, 10L, 97_000L);
        registry.record("/pay", CallStatus.FAILURE, 20L, 99_000L);
        registry.record("/pay", CallStatus.SUCCESS, 30L, 100_000L);
        WindowSnapshot realtimeAt100k = registry.snapshot("/pay").orElseThrow();
        logSnapshot("t=100000 实时快照", realtimeAt100k);

        // 时钟推进到 104s（97s 的桶仍在保留范围 [now-window, now] 内）、写入新数据后回看同一时刻
        clock.setMillis(104_000L);
        registry.record("/pay", CallStatus.SUCCESS, 40L, 104_000L);
        WindowSnapshot lookback = registry.snapshotAt("/pay", 100_000L).orElseThrow();
        logSnapshot("时钟推进到 t=104000 后回看 t=100000", lookback);
        assertEquals(realtimeAt100k, lookback, "回看结果必须与当时实时快照完全一致");

        // 补报一条属于该窗口的历史数据后，回看与"当时就近实时写入"的口径一致
        registry.backfill(List.of(CallEvent.of("/pay", CallStatus.FAILURE, 55L, 98_000L)));
        WindowSnapshot afterBackfill = registry.snapshotAt("/pay", 100_000L).orElseThrow();
        logSnapshot("补报 1 条 t=98000 后回看 t=100000", afterBackfill);
        log.info("判定依据：补报前 total={}，补报 1 条失败（延迟55）后 total={} failure={} avg={}",
                lookback.callCount(), afterBackfill.callCount(), afterBackfill.failureCount(),
                String.format("%.2f", afterBackfill.avgLatencyMillis()));
        assertAll("补报后回看口径",
                () -> assertEquals(4L, afterBackfill.callCount()),
                () -> assertEquals(2L, afterBackfill.failureCount()),
                () -> assertEquals(115d / 4, afterBackfill.avgLatencyMillis(), 1e-9));
    }

    @Test
    void snapshotAtRejectsFutureAndReturnsEmptyForRolledOutHistory() {
        FixedClock clock = new FixedClock(100_000L);
        MetricsRegistry registry = newRegistry(clock);
        registry.record("/api", CallStatus.SUCCESS, 10L, 100_000L);

        InvalidMetricException future = assertThrows(InvalidMetricException.class,
                () -> registry.snapshotAt("/api", 100_001L));
        log.info("[未来时刻拒绝] 原因码={} 说明={}", future.reason(), future.getMessage());
        assertEquals(MetricsRegistry.REASON_TIMESTAMP_IN_FUTURE, future.reason());

        InvalidMetricException blank = assertThrows(InvalidMetricException.class,
                () -> registry.snapshotAt(" ", 100_000L));
        assertEquals(MetricsRegistry.REASON_BLANK_API, blank.reason());

        // 时钟推进到窗口完全滚出，触发一次淘汰后，历史时刻回看为空（与"窗口内无指标"一致）
        clock.setMillis(200_000L);
        registry.snapshot("/api"); // 触发过期桶淘汰
        Optional<WindowSnapshot> rolledOut = registry.snapshotAt("/api", 100_000L);
        log.info("窗口滚出后回看 t=100000：快照存在? {}", rolledOut.isPresent());
        assertTrue(rolledOut.isEmpty(), "数据已销毁的历史时刻返回空，且结果稳定");
        assertTrue(registry.snapshotAt("/api", 100_000L).isEmpty(), "反复查询结果稳定");
        assertTrue(registry.snapshotAt("/never-called", 100_000L).isEmpty(),
                "从未上报的接口回看为空");
    }
}
