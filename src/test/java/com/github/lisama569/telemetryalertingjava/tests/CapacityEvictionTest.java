package com.github.lisama569.telemetryalertingjava.tests;

import com.github.lisama569.telemetryalertingjava.core.FixedClock;
import com.github.lisama569.telemetryalertingjava.metrics.BackfillReport;
import com.github.lisama569.telemetryalertingjava.metrics.CallEvent;
import com.github.lisama569.telemetryalertingjava.metrics.CallStatus;
import com.github.lisama569.telemetryalertingjava.metrics.EvictionStats;
import com.github.lisama569.telemetryalertingjava.metrics.MetricsRegistry;
import com.github.lisama569.telemetryalertingjava.metrics.WindowSnapshot;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 容量约束测试：保留上限、最旧优先淘汰、淘汰量单独暴露、无负值、与批量补报并存。
 */
class CapacityEvictionTest {

    private static final Logger log = LoggerFactory.getLogger(CapacityEvictionTest.class);

    private void logStats(String title, EvictionStats s) {
        log.info("{} | api={} cap={} retained={} evictedSamples={} evictedBuckets={}",
                title, s.api(), s.maxSamplesPerApi(), s.retainedSamples(),
                s.evictedSamples(), s.evictedBuckets());
    }

    @Test
    void evictsOldestSamplesFirstWhenCapReached() {
        FixedClock clock = new FixedClock(100_000L);
        // 10s 窗口、1s 桶、每接口最多保留 5 个样本
        MetricsRegistry registry = new MetricsRegistry(10_000L, 1_000L, clock, 5L);

        // 先写 3 条到旧桶 95000（延迟 1,2,3），再写 5 条到当前桶（延迟 4..8）
        for (int i = 1; i <= 3; i++) {
            registry.record("/order", CallStatus.SUCCESS, i, 95_000L + i);
        }
        for (int i = 4; i <= 8; i++) {
            registry.record("/order", i % 2 == 0 ? CallStatus.SUCCESS : CallStatus.FAILURE,
                    i, 100_000L);
        }
        log.info("输入：t=95000 写 3 条（延迟1,2,3），t=100000 写 5 条（延迟4..8），cap=5");

        WindowSnapshot s = registry.snapshot("/order").orElseThrow();
        EvictionStats stats = registry.evictionStats("/order");
        log.info("淘汰后聚合 | total={} success={} failure={} latencies={}",
                s.callCount(), s.successCount(), s.failureCount(), s.sortedLatenciesMillis());
        logStats("容量淘汰统计", stats);

        assertAll("最旧样本优先淘汰，固定可预期",
                () -> assertEquals(5L, s.callCount(), "只保留最新 5 条"),
                () -> assertEquals(List.of(4L, 5L, 6L, 7L, 8L), s.sortedLatenciesMillis(),
                        "延迟 1,2,3 的最旧样本被淘汰"),
                () -> assertEquals(5L, stats.retainedSamples()),
                () -> assertEquals(3L, stats.evictedSamples(), "淘汰量单独暴露"),
                () -> assertEquals(1L, stats.evictedBuckets(), "旧桶被清空后整体移除"),
                () -> assertEquals(5L, stats.maxSamplesPerApi()),
                () -> assertTrue(s.successCount() >= 0 && s.failureCount() >= 0
                        && s.callCount() == s.successCount() + s.failureCount(),
                        "计数非负且自洽"));
    }

    @Test
    void evictionNeverProducesNegativeOrInconsistentCountsUnderConcurrency()
            throws InterruptedException {
        FixedClock clock = new FixedClock(100_000L);
        MetricsRegistry registry = new MetricsRegistry(10_000L, 1_000L, clock, 3L);
        int writes = 2_000;

        AtomicReference<String> violation = new AtomicReference<>(null);
        CountDownLatch start = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            await(start);
            for (int i = 0; i < writes; i++) {
                registry.record("/hot", i % 3 == 0 ? CallStatus.FAILURE : CallStatus.SUCCESS,
                        1L, 100_000L);
            }
        });
        Thread reader = new Thread(() -> {
            await(start);
            for (int i = 0; i < writes; i++) {
                registry.snapshot("/hot").ifPresent(s -> {
                    if (s.callCount() < 0 || s.callCount() > 3
                            || s.successCount() < 0 || s.failureCount() < 0
                            || s.callCount() != s.successCount() + s.failureCount()) {
                        violation.compareAndSet(null, "错乱快照: " + s);
                    }
                });
            }
        });
        writer.start();
        reader.start();
        start.countDown();
        writer.join(10_000);
        reader.join(10_000);
        assertAll("线程必须结束",
                () -> assertFalse(writer.isAlive()), () -> assertFalse(reader.isAlive()));

        EvictionStats stats = registry.evictionStats("/hot");
        WindowSnapshot s = registry.snapshot("/hot").orElseThrow();
        log.info("并发写 {} 条（cap=3）期间并发读 {} 次", writes, writes);
        logStats("最终容量统计", stats);
        log.info("最终聚合 total={} success={} failure={}",
                s.callCount(), s.successCount(), s.failureCount());

        assertAll("淘汰过程不出现负值、统计不错乱",
                () -> assertNull(violation.get(), "任何时刻读到的快照都必须自洽"),
                () -> assertEquals(3L, s.callCount(), "保留量恒等于上限"),
                () -> assertEquals(3L, stats.retainedSamples()),
                () -> assertEquals((long) writes - 3, stats.evictedSamples(),
                        "写入总数 = 保留 + 容量淘汰"),
                () -> assertEquals((long) writes,
                        stats.retainedSamples() + stats.evictedSamples(), "总量守恒"));
    }

    @Test
    void backfillAndCapacityEvictionTogetherKeepAccountingConsistent() {
        FixedClock clock = new FixedClock(100_000L);
        MetricsRegistry registry = new MetricsRegistry(10_000L, 1_000L, clock, 10L);

        // 实时写 6 条（延迟 1000 标记实时流量）
        for (int i = 0; i < 6; i++) {
            registry.record("/api", CallStatus.SUCCESS, 1_000L, 100_000L);
        }
        // 乱序补报 20 条历史数据（延迟 1..20，时间戳 91000~98600 倒置）
        List<CallEvent> batch = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            batch.add(CallEvent.of("/api",
                    i % 2 == 0 ? CallStatus.SUCCESS : CallStatus.FAILURE,
                    (long) (i + 1), 91_000L + i * 400L));
        }
        Collections.reverse(batch);
        log.info("输入：实时 6 条（延迟1000）+ 乱序补报 20 条（延迟1..20），cap=10");
        BackfillReport report = registry.backfill(batch);
        log.info("补报回执 | received={} accepted={} rejected={} slots={}",
                report.received(), report.accepted(), report.rejected(), report.slots());

        EvictionStats stats = registry.evictionStats("/api");
        WindowSnapshot s = registry.snapshot("/api").orElseThrow();
        logStats("容量统计", stats);
        log.info("聚合 | total={} success={} failure={} latencies={}",
                s.callCount(), s.successCount(), s.failureCount(), s.sortedLatenciesMillis());

        assertAll("补报全部接受，但保留量受上限约束",
                () -> assertEquals(20, report.accepted(), "回执按接受口径统计，与容量淘汰分开"),
                () -> assertEquals(10L, s.callCount(), "保留量不超过上限"),
                () -> assertEquals(10L, stats.retainedSamples()),
                () -> assertEquals(16L, stats.evictedSamples(),
                        "26 条写入 - 10 条保留 = 16 条容量淘汰"),
                () -> assertEquals(26L, stats.retainedSamples() + stats.evictedSamples(),
                        "总量守恒：实时 6 + 补报 20"),
                () -> assertTrue(s.callCount() == s.successCount() + s.failureCount()
                        && s.successCount() >= 0 && s.failureCount() >= 0, "计数非负且自洽"),
                () -> assertTrue(s.sortedLatenciesMillis().stream().filter(l -> l == 1_000L)
                                .count() <= 6,
                        "实时样本最多保留 6 条（最旧优先淘汰）"));

        // 容量淘汰后回看仍保留的时刻，结果稳定
        WindowSnapshot lookback = registry.snapshotAt("/api", 100_000L).orElseThrow();
        assertEquals(lookback, registry.snapshotAt("/api", 100_000L).orElseThrow(),
                "容量淘汰后同一时刻回看结果稳定");
        log.info("回看 t=100000 | total={}（与当前窗口一致，因未再推进时钟）", lookback.callCount());
    }

    @Test
    void evictionStatsOfUnknownApiIsZeroAndUnlimitedByDefault() {
        FixedClock clock = new FixedClock(100_000L);
        MetricsRegistry unlimited = new MetricsRegistry(10_000L, 1_000L, clock);
        EvictionStats zero = unlimited.evictionStats("/never-called");
        logStats("未上报接口的容量统计", zero);
        assertAll("未上报接口返回全零统计",
                () -> assertEquals(0L, zero.retainedSamples()),
                () -> assertEquals(0L, zero.evictedSamples()),
                () -> assertEquals(0L, zero.maxSamplesPerApi(), "默认不限容量"));

        for (int i = 0; i < 100; i++) {
            unlimited.record("/api", CallStatus.SUCCESS, 1L, 100_000L);
        }
        EvictionStats after = unlimited.evictionStats("/api");
        logStats("默认构造（不限容量）写 100 条后", after);
        assertAll("默认行为与既有版本一致：不淘汰",
                () -> assertEquals(100L, after.retainedSamples()),
                () -> assertEquals(0L, after.evictedSamples()));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
