package com.github.lisama569.telemetryalertingjava.tests;

import com.github.lisama569.telemetryalertingjava.core.FixedClock;
import com.github.lisama569.telemetryalertingjava.metrics.CallStatus;
import com.github.lisama569.telemetryalertingjava.metrics.InvalidMetricException;
import com.github.lisama569.telemetryalertingjava.metrics.MetricsRegistry;
import com.github.lisama569.telemetryalertingjava.metrics.WindowSnapshot;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 按历史时刻回看测试：只反映该时刻及所在窗口内的数据、不受后续写入影响、重复查询稳定。
 */
class PointInTimeSnapshotTest {

    private static final Logger log = LoggerFactory.getLogger(PointInTimeSnapshotTest.class);

    /** 10s 窗口、1s 桶、60s 保留期：允许回看最近 60s 内的任意时刻。 */
    private MetricsRegistry newRegistry(FixedClock clock) {
        return new MetricsRegistry(10_000L, 1_000L, 60_000L, 0L, clock);
    }

    private void logSnapshot(String title, WindowSnapshot s) {
        log.info("{} | api={} window=[{},{}] total={} success={} failure={} avgLatency={} maxLatency={}",
                title, s.api(), s.windowStartMillis(), s.windowEndMillis(),
                s.callCount(), s.successCount(), s.failureCount(),
                String.format("%.2f", s.avgLatencyMillis()), s.maxLatencyMillis());
    }

    @Test
    void snapshotAtReflectsOnlyDataUpToThatMoment() {
        FixedClock clock = new FixedClock(0L);
        MetricsRegistry registry = newRegistry(clock);

        // 第一批：t=0..4s 各 1 条成功（延迟 10..50）
        for (int i = 0; i < 5; i++) {
            clock.setMillis(i * 1_000L);
            registry.record("/order", CallStatus.SUCCESS, (i + 1) * 10L);
        }
        // 在 t=4s 取一次实时快照作为"当时真值"
        clock.setMillis(4_000L);
        WindowSnapshot liveAt4s = registry.snapshot("/order").orElseThrow();
        logSnapshot("t=4s 实时快照（真值）", liveAt4s);

        // 第二批：t=20..24s 各 1 条失败（延迟 100）
        for (int i = 0; i < 5; i++) {
            clock.setMillis(20_000L + i * 1_000L);
            registry.record("/order", CallStatus.FAILURE, 100L);
        }
        log.info("后续又写入 5 条失败（t∈[20s,24s]），now 推进到 30s");
        clock.setMillis(30_000L);

        // 回看 t=4s：必须只含第一批，与当时实时快照一致
        WindowSnapshot at4s = registry.snapshotAt("/order", 4_000L).orElseThrow();
        logSnapshot("now=30s 回看 t=4s", at4s);
        assertAll("回看 t=4s 与当时实时快照一致",
                () -> assertEquals(liveAt4s.callCount(), at4s.callCount()),
                () -> assertEquals(liveAt4s.successCount(), at4s.successCount()),
                () -> assertEquals(liveAt4s.failureCount(), at4s.failureCount()),
                () -> assertEquals(liveAt4s.sortedLatenciesMillis(), at4s.sortedLatenciesMillis()),
                () -> assertEquals(0L, at4s.failureCount(), "之后的失败不得混入"),
                () -> assertEquals(4_000L, at4s.windowEndMillis()),
                () -> assertEquals(4_000L - 10_000L, at4s.windowStartMillis()));

        // 回看 t=22s：窗口 [12s,22s]，只含 t=20,21,22 三条失败
        WindowSnapshot at22s = registry.snapshotAt("/order", 22_000L).orElseThrow();
        logSnapshot("回看 t=22s", at22s);
        assertAll("回看 t=22s 只含窗口内数据",
                () -> assertEquals(3L, at22s.callCount()),
                () -> assertEquals(3L, at22s.failureCount()),
                () -> assertEquals(0L, at22s.successCount(), "t<=4s 的成功已滚出该窗口"));
    }

    @Test
    void snapshotAtIsStableAcrossRepeatedQueriesAndLaterWrites() {
        FixedClock clock = new FixedClock(50_000L);
        MetricsRegistry registry = newRegistry(clock);
        registry.record("/pay", CallStatus.SUCCESS, 10L, 49_000L);
        registry.record("/pay", CallStatus.FAILURE, 20L, 50_000L);

        WindowSnapshot first = registry.snapshotAt("/pay", 50_000L).orElseThrow();
        logSnapshot("第 1 次回看 t=50s", first);

        // 之后又有新写入（事件时间戳晚于 t=50s）、时钟继续推进
        clock.setMillis(51_000L);
        registry.record("/pay", CallStatus.FAILURE, 999L, 51_000L);
        clock.setMillis(55_000L);
        registry.record("/pay", CallStatus.FAILURE, 888L, 55_000L);
        log.info("回看之后又写入 2 条失败（t=51s、55s，均晚于回看时刻）并推进时钟到 55s");

        WindowSnapshot second = registry.snapshotAt("/pay", 50_000L).orElseThrow();
        WindowSnapshot third = registry.snapshotAt("/pay", 50_000L).orElseThrow();
        logSnapshot("第 2 次回看 t=50s", second);
        log.info("判定依据: 三次回看 total={}/{}/{} 一致且不含 t=50s 之后的写入",
                first.callCount(), second.callCount(), third.callCount());

        assertAll("回看结果稳定",
                () -> assertEquals(first, second, "后续写入不得改变历史时刻的回看结果"),
                () -> assertEquals(second, third, "同一时刻反复查询结果一致"),
                () -> assertEquals(2L, second.callCount()),
                () -> assertEquals(50_000L, second.windowEndMillis()));
    }

    @Test
    void snapshotAtReflectsBackfilledDataBelongingToThatWindow() {
        FixedClock clock = new FixedClock(50_000L);
        MetricsRegistry registry = newRegistry(clock);
        registry.record("/pay", CallStatus.SUCCESS, 10L, 49_000L);

        WindowSnapshot before = registry.snapshotAt("/pay", 49_000L).orElseThrow();
        logSnapshot("补报前回看 t=49s", before);
        assertEquals(1L, before.callCount());

        // 补报一条本来就属于 t=48s 的历史事件：回看应反映补全后的历史，
        // 与"当时就近实时写入"的口径一致（这正是补报的意义）
        registry.backfill(java.util.List.of(
                com.github.lisama569.telemetryalertingjava.metrics.CallEvent.of(
                        "/pay", CallStatus.FAILURE, 30L, 48_000L)));
        WindowSnapshot after = registry.snapshotAt("/pay", 49_000L).orElseThrow();
        logSnapshot("补报后回看 t=49s", after);
        log.info("判定依据: 补报前 total={} -> 补报后 total={}，补报事件落回其所属窗口",
                before.callCount(), after.callCount());
        assertAll("补报后回看反映补全的历史",
                () -> assertEquals(2L, after.callCount()),
                () -> assertEquals(1L, after.failureCount()),
                () -> assertEquals(after, registry.snapshotAt("/pay", 49_000L).orElseThrow(),
                        "补报完成后同一时刻反复查询保持稳定"));
    }

    @Test
    void snapshotAtRejectsFutureAndReflectsRetentionLimits() {
        FixedClock clock = new FixedClock(100_000L);
        MetricsRegistry registry = newRegistry(clock);
        registry.record("/a", CallStatus.SUCCESS, 10L, 95_000L);

        InvalidMetricException ex = assertThrows(InvalidMetricException.class,
                () -> registry.snapshotAt("/a", 100_001L));
        log.info("未来时刻回看被拒绝: reason={} msg={}", ex.reason(), ex.getMessage());
        assertEquals(MetricsRegistry.REASON_TIMESTAMP_IN_FUTURE, ex.reason());

        // 超出保留期的时刻：数据已被淘汰，回看返回空（与"窗口内无数据"口径一致）
        assertTrue(registry.snapshotAt("/a", 30_000L).isEmpty(),
                "t=30s 的窗口 [20s,30s] 已超出保留期，返回空");
        log.info("t=30s 回看为空：该时段数据已超出 60s 保留期被淘汰");
    }
}
