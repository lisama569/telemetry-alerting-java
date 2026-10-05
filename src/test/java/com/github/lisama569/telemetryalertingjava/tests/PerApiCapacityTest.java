package com.github.lisama569.telemetryalertingjava.tests;

import com.github.lisama569.telemetryalertingjava.core.FixedClock;
import com.github.lisama569.telemetryalertingjava.metrics.BackfillReport;
import com.github.lisama569.telemetryalertingjava.metrics.CallEvent;
import com.github.lisama569.telemetryalertingjava.metrics.CallStatus;
import com.github.lisama569.telemetryalertingjava.metrics.CapacityInfo;
import com.github.lisama569.telemetryalertingjava.metrics.EvictionStats;
import com.github.lisama569.telemetryalertingjava.metrics.InvalidMetricException;
import com.github.lisama569.telemetryalertingjava.metrics.MetricsRegistry;
import com.github.lisama569.telemetryalertingjava.metrics.WindowSnapshot;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 按接口保留上限测试：不同接口不同上限、运行期调整立即生效、无效上限明确拒绝、
 * 上限调整与容量淘汰、批量乱序补报并发时口径一致（无负值、总量守恒）。
 */
class PerApiCapacityTest {

    private static final Logger log = LoggerFactory.getLogger(PerApiCapacityTest.class);

    private static void logInfo(String title, CapacityInfo c) {
        log.info("{} | api={} effectiveCap={} defaultCap={} overridden={}",
                title, c.api(), c.maxSamplesPerApi(), c.defaultMaxSamplesPerApi(), c.overridden());
    }

    private static void logStats(String title, EvictionStats s) {
        log.info("{} | api={} cap={} retained={} evictedSamples={} evictedBuckets={}",
                title, s.api(), s.maxSamplesPerApi(), s.retainedSamples(),
                s.evictedSamples(), s.evictedBuckets());
    }

    @Test
    void differentApisCanHaveDifferentCaps() {
        FixedClock clock = new FixedClock(100_000L);
        // 全局默认上限 5；/small 单独设 2，/big 单独设 8，/default 不设置走默认
        MetricsRegistry registry = new MetricsRegistry(10_000L, 1_000L, clock, 5L);
        logInfo("设置 /small 上限", registry.setMaxSamples("/small", 2L));
        logInfo("设置 /big 上限", registry.setMaxSamples("/big", 8L));
        logInfo("查询 /default 上限（未单独设置）", registry.capacityInfo("/default"));

        for (int i = 0; i < 10; i++) {
            registry.record("/small", CallStatus.SUCCESS, 1L, 100_000L);
            registry.record("/big", CallStatus.SUCCESS, 1L, 100_000L);
            registry.record("/default", CallStatus.SUCCESS, 1L, 100_000L);
        }
        log.info("输入：三个接口各写 10 条，cap: /small=2, /big=8, /default=默认5");

        EvictionStats small = registry.evictionStats("/small");
        EvictionStats big = registry.evictionStats("/big");
        EvictionStats dflt = registry.evictionStats("/default");
        logStats("/small 容量统计", small);
        logStats("/big 容量统计", big);
        logStats("/default 容量统计", dflt);

        assertAll("不同接口按各自上限保留，淘汰量按接口分别统计",
                () -> assertEquals(2L, small.retainedSamples()),
                () -> assertEquals(8L, small.evictedSamples()),
                () -> assertEquals(2L, small.maxSamplesPerApi(), "生效上限是单独设置的值"),
                () -> assertEquals(8L, big.retainedSamples()),
                () -> assertEquals(2L, big.evictedSamples()),
                () -> assertEquals(8L, big.maxSamplesPerApi()),
                () -> assertEquals(5L, dflt.retainedSamples()),
                () -> assertEquals(5L, dflt.evictedSamples()),
                () -> assertEquals(5L, dflt.maxSamplesPerApi(), "未单独设置的接口走全局默认值"),
                () -> assertFalse(registry.capacityInfo("/default").overridden()),
                () -> assertTrue(registry.capacityInfo("/small").overridden()),
                () -> assertEquals(5L, registry.capacityInfo("/small").defaultMaxSamplesPerApi()));
    }

    @Test
    void runtimeAdjustmentTakesEffectImmediatelyForSubsequentWrites() {
        FixedClock clock = new FixedClock(100_000L);
        // 默认不限容量
        MetricsRegistry registry = new MetricsRegistry(10_000L, 1_000L, clock);

        for (int i = 0; i < 10; i++) {
            registry.record("/api", CallStatus.SUCCESS, (long) (i + 1), 100_000L);
        }
        assertEquals(10L, registry.evictionStats("/api").retainedSamples(), "默认不限，全部保留");

        // 运行期调低到 4：立即把最旧的 6 条淘汰到位，无需重启
        logInfo("运行期调低上限到 4", registry.setMaxSamples("/api", 4L));
        EvictionStats afterShrink = registry.evictionStats("/api");
        logStats("调低后容量统计", afterShrink);
        WindowSnapshot snap = registry.snapshot("/api").orElseThrow();
        log.info("调低后聚合 | total={} latencies={}",
                snap.callCount(), snap.sortedLatenciesMillis());
        assertAll("调低立即生效",
                () -> assertEquals(4L, afterShrink.retainedSamples()),
                () -> assertEquals(6L, afterShrink.evictedSamples(), "超出的 6 条计入容量淘汰"),
                () -> assertEquals(List.of(7L, 8L, 9L, 10L), snap.sortedLatenciesMillis(),
                        "最旧样本优先被淘汰"),
                () -> assertEquals(10L,
                        afterShrink.retainedSamples() + afterShrink.evictedSamples(), "总量守恒"));

        // 后续写入立刻受新上限约束
        for (int i = 0; i < 3; i++) {
            registry.record("/api", CallStatus.SUCCESS, 100L + i, 100_000L);
        }
        EvictionStats afterMore = registry.evictionStats("/api");
        logStats("再写 3 条后容量统计", afterMore);
        assertAll("后续写入立即受新上限约束",
                () -> assertEquals(4L, afterMore.retainedSamples()),
                () -> assertEquals(9L, afterMore.evictedSamples()));

        // 运行期调高到 6：之后写入可以保留更多
        logInfo("运行期调高上限到 6", registry.setMaxSamples("/api", 6L));
        for (int i = 0; i < 5; i++) {
            registry.record("/api", CallStatus.SUCCESS, 200L + i, 100_000L);
        }
        EvictionStats afterRaise = registry.evictionStats("/api");
        logStats("调高后再写 5 条容量统计", afterRaise);
        assertAll("调高后保留量随之上升",
                () -> assertEquals(6L, afterRaise.retainedSamples()),
                () -> assertEquals(12L, afterRaise.evictedSamples()),
                () -> assertEquals(18L,
                        afterRaise.retainedSamples() + afterRaise.evictedSamples(),
                        "累计写入 18 条 = 保留 6 + 淘汰 12"));

        // 清除单独设置，恢复默认（不限）
        logInfo("清除单独上限", registry.clearMaxSamples("/api"));
        for (int i = 0; i < 5; i++) {
            registry.record("/api", CallStatus.SUCCESS, 300L + i, 100_000L);
        }
        EvictionStats afterClear = registry.evictionStats("/api");
        logStats("清除后再写 5 条容量统计", afterClear);
        assertAll("清除后走默认值（不限），淘汰数不再增长",
                () -> assertEquals(11L, afterClear.retainedSamples()),
                () -> assertEquals(12L, afterClear.evictedSamples()),
                () -> assertEquals(0L, afterClear.maxSamplesPerApi()),
                () -> assertFalse(registry.capacityInfo("/api").overridden()));
    }

    @Test
    void invalidCapacityIsRejectedAndStateUnchanged() {
        FixedClock clock = new FixedClock(100_000L);
        MetricsRegistry registry = new MetricsRegistry(10_000L, 1_000L, clock, 5L);
        registry.setMaxSamples("/api", 3L);
        for (int i = 0; i < 5; i++) {
            registry.record("/api", CallStatus.SUCCESS, (long) (i + 1), 100_000L);
        }
        EvictionStats before = registry.evictionStats("/api");
        logStats("无效设置前容量统计", before);

        InvalidMetricException zero = assertThrows(InvalidMetricException.class,
                () -> registry.setMaxSamples("/api", 0L));
        InvalidMetricException negative = assertThrows(InvalidMetricException.class,
                () -> registry.setMaxSamples("/api", -7L));
        InvalidMetricException blank = assertThrows(InvalidMetricException.class,
                () -> registry.setMaxSamples("  ", 10L));
        log.info("拒绝 0 -> {}: {}", zero.reason(), zero.getMessage());
        log.info("拒绝负数 -> {}: {}", negative.reason(), negative.getMessage());
        log.info("拒绝空白接口 -> {}: {}", blank.reason(), blank.getMessage());

        EvictionStats after = registry.evictionStats("/api");
        CapacityInfo info = registry.capacityInfo("/api");
        logStats("无效设置后容量统计", after);
        logInfo("无效设置后生效上限", info);
        assertAll("无效上限被明确拒绝，上限与统计保持原样",
                () -> assertEquals(MetricsRegistry.REASON_INVALID_CAPACITY, zero.reason()),
                () -> assertEquals(MetricsRegistry.REASON_INVALID_CAPACITY, negative.reason()),
                () -> assertEquals(MetricsRegistry.REASON_BLANK_API, blank.reason()),
                () -> assertEquals(3L, info.maxSamplesPerApi(), "上限未被悄悄改掉"),
                () -> assertTrue(info.overridden()),
                () -> assertEquals(before, after, "淘汰统计未被搞乱"),
                () -> assertEquals(3L, registry.snapshot("/api").orElseThrow().callCount(),
                        "窗口数据未受影响"));
    }

    @Test
    void capacityEvictionConcurrentWithBackfillAndAdjustmentKeepsAccounting()
            throws InterruptedException {
        FixedClock clock = new FixedClock(1_000_000L);
        // 60s 窗口、1s 桶；默认上限 0（不限），并发期间运行期调整 /hot 的单独上限
        MetricsRegistry registry = new MetricsRegistry(60_000L, 1_000L, clock);
        int realtimeWrites = 2_000;
        int backfillBatches = 50;
        int batchSize = 20;

        AtomicLong acceptedTotal = new AtomicLong();
        AtomicReference<String> violation = new AtomicReference<>(null);
        CountDownLatch start = new CountDownLatch(1);

        Thread writer = new Thread(() -> {
            await(start);
            for (int i = 0; i < realtimeWrites; i++) {
                registry.record("/hot", i % 4 == 0 ? CallStatus.FAILURE : CallStatus.SUCCESS,
                        1L, 1_000_000L);
                acceptedTotal.incrementAndGet();
            }
        });
        Thread backfiller = new Thread(() -> {
            await(start);
            for (int b = 0; b < backfillBatches; b++) {
                List<CallEvent> batch = new ArrayList<>();
                for (int i = 0; i < batchSize; i++) {
                    // 乱序：批内时间戳倒置，散布在窗口内
                    batch.add(CallEvent.of("/hot",
                            i % 3 == 0 ? CallStatus.FAILURE : CallStatus.SUCCESS,
                            2L, 1_000_000L - (long) (i + 1) * 1_000L));
                }
                BackfillReport report = registry.backfill(batch);
                acceptedTotal.addAndGet(report.accepted());
                if (report.rejected() != 0) {
                    violation.compareAndSet(null, "补报出现意外拒绝: " + report.rejections());
                }
            }
        });
        // 运行期反复调整上限（只使用合法正数），与写入/补报并发
        Thread adjuster = new Thread(() -> {
            await(start);
            long[] caps = {50L, 500L, 100L, 1_000L, 200L};
            for (int i = 0; i < 200; i++) {
                registry.setMaxSamples("/hot", caps[i % caps.length]);
            }
        });
        Thread reader = new Thread(() -> {
            await(start);
            for (int i = 0; i < 4_000; i++) {
                registry.snapshot("/hot").ifPresent(s -> {
                    if (s.callCount() < 0 || s.successCount() < 0 || s.failureCount() < 0
                            || s.callCount() != s.successCount() + s.failureCount()) {
                        violation.compareAndSet(null, "错乱快照: " + s);
                    }
                });
                EvictionStats stats = registry.evictionStats("/hot");
                if (stats.retainedSamples() < 0 || stats.evictedSamples() < 0
                        || stats.maxSamplesPerApi() < 0) {
                    violation.compareAndSet(null, "负值容量统计: " + stats);
                }
            }
        });

        writer.start();
        backfiller.start();
        adjuster.start();
        reader.start();
        start.countDown();
        writer.join(30_000);
        backfiller.join(30_000);
        adjuster.join(30_000);
        reader.join(30_000);
        assertAll("线程必须结束",
                () -> assertFalse(writer.isAlive()), () -> assertFalse(backfiller.isAlive()),
                () -> assertFalse(adjuster.isAlive()), () -> assertFalse(reader.isAlive()));
        assertNull(violation.get(), "并发期间任何时刻读到的统计都不能为负或错乱");

        // 并发结束后补一波写入把保留量充满（并发期间调低上限淘汰掉的样本不会回来），
        // 再把上限固定到 100，账务必须完全对得上：
        // 接受总数 = 当前保留 + 容量淘汰（时钟固定，无自然滚动淘汰）
        for (int i = 0; i < 1_000; i++) {
            registry.record("/hot", CallStatus.SUCCESS, 1L, 1_000_000L);
            acceptedTotal.incrementAndGet();
        }
        logInfo("并发结束后固定上限", registry.setMaxSamples("/hot", 100L));
        EvictionStats stats = registry.evictionStats("/hot");
        WindowSnapshot snap = registry.snapshot("/hot").orElseThrow();
        long accepted = acceptedTotal.get();
        log.info("并发输入：实时 {} 条 + 补报 {} 批 x {} 条 + 收尾 1000 条，接受总数={}",
                realtimeWrites, backfillBatches, batchSize, accepted);
        logStats("最终容量统计", stats);
        log.info("最终聚合 | total={} success={} failure={}",
                snap.callCount(), snap.successCount(), snap.failureCount());

        assertAll("上限调整+容量淘汰+批量补报并发后口径一致",
                () -> assertEquals(realtimeWrites + (long) backfillBatches * batchSize + 1_000L,
                        accepted, "全部写入都被接受（固定时钟下无过期拒绝）"),
                () -> assertEquals(100L, stats.retainedSamples(), "保留量等于最终上限"),
                () -> assertEquals(100L, snap.callCount()),
                () -> assertEquals(accepted - 100L, stats.evictedSamples(),
                        "容量淘汰量 = 接受总数 - 保留量"),
                () -> assertEquals(accepted,
                        stats.retainedSamples() + stats.evictedSamples(), "总量守恒，不多不少"),
                () -> assertEquals(100L, stats.maxSamplesPerApi()),
                () -> assertTrue(snap.successCount() >= 0 && snap.failureCount() >= 0
                        && snap.callCount() == snap.successCount() + snap.failureCount(),
                        "计数非负且自洽"));
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
