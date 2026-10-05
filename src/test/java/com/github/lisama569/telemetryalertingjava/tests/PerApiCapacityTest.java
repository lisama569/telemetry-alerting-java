package com.github.lisama569.telemetryalertingjava.tests;

import com.github.lisama569.telemetryalertingjava.core.FixedClock;
import com.github.lisama569.telemetryalertingjava.metrics.BackfillReport;
import com.github.lisama569.telemetryalertingjava.metrics.CallEvent;
import com.github.lisama569.telemetryalertingjava.metrics.CallStatus;
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
 * 上限调整与容量淘汰、批量补报并发时账目一致且无负值。
 */
class PerApiCapacityTest {

    private static final Logger log = LoggerFactory.getLogger(PerApiCapacityTest.class);

    private void logStats(String title, EvictionStats s) {
        log.info("{} | api={} cap={} retained={} evictedSamples={} evictedBuckets={}",
                title, s.api(), s.maxSamplesPerApi(), s.retainedSamples(),
                s.evictedSamples(), s.evictedBuckets());
    }

    @Test
    void differentApisCanHaveDifferentLimitsAndOthersFallBackToDefault() {
        FixedClock clock = new FixedClock(100_000L);
        // 默认上限 10：未单独设置的接口走默认值
        MetricsRegistry registry = new MetricsRegistry(10_000L, 1_000L, clock, 10L);
        registry.setMaxSamplesPerApi("/small", 3L);
        registry.setMaxSamplesPerApi("/big", 50L);
        log.info("设置：default=10, /small=3, /big=50, /other 走默认");

        assertAll("生效上限按接口独立查询",
                () -> assertEquals(3L, registry.maxSamplesPerApi("/small")),
                () -> assertEquals(50L, registry.maxSamplesPerApi("/big")),
                () -> assertEquals(10L, registry.maxSamplesPerApi("/other"), "未设置的走默认值"),
                () -> assertTrue(registry.hasMaxSamplesOverride("/small")),
                () -> assertFalse(registry.hasMaxSamplesOverride("/other")));

        for (int i = 0; i < 20; i++) {
            registry.record("/small", CallStatus.SUCCESS, 1L, 100_000L);
            registry.record("/big", CallStatus.SUCCESS, 1L, 100_000L);
            registry.record("/other", CallStatus.SUCCESS, 1L, 100_000L);
        }
        EvictionStats small = registry.evictionStats("/small");
        EvictionStats big = registry.evictionStats("/big");
        EvictionStats other = registry.evictionStats("/other");
        logStats("/small", small);
        logStats("/big", big);
        logStats("/other（默认）", other);

        assertAll("各接口按各自上限淘汰，统计分别暴露、不带成同一个上限值",
                () -> assertEquals(3L, small.retainedSamples()),
                () -> assertEquals(17L, small.evictedSamples()),
                () -> assertEquals(3L, small.maxSamplesPerApi()),
                () -> assertEquals(20L, big.retainedSamples(), "上限 50，写 20 不淘汰"),
                () -> assertEquals(0L, big.evictedSamples()),
                () -> assertEquals(50L, big.maxSamplesPerApi()),
                () -> assertEquals(10L, other.retainedSamples()),
                () -> assertEquals(10L, other.evictedSamples()),
                () -> assertEquals(10L, other.maxSamplesPerApi(), "默认上限"));
    }

    @Test
    void runtimeLimitChangeTakesEffectImmediatelyForSubsequentWrites() {
        FixedClock clock = new FixedClock(100_000L);
        MetricsRegistry registry = new MetricsRegistry(10_000L, 1_000L, clock, 0L);

        for (int i = 0; i < 8; i++) {
            registry.record("/api", CallStatus.SUCCESS, 1L, 100_000L);
        }
        assertEquals(8L, registry.evictionStats("/api").retainedSamples());
        log.info("默认不限，写 8 条全部保留");

        // 运行期调小到 5：立即淘汰到位，后续写入也按新上限
        registry.setMaxSamplesPerApi("/api", 5L);
        EvictionStats afterShrink = registry.evictionStats("/api");
        logStats("调小到 5 后", afterShrink);
        assertAll("调小立即生效",
                () -> assertEquals(5L, afterShrink.retainedSamples()),
                () -> assertEquals(3L, afterShrink.evictedSamples()),
                () -> assertEquals(5L, afterShrink.maxSamplesPerApi()));

        registry.record("/api", CallStatus.SUCCESS, 1L, 100_000L);
        EvictionStats afterWrite = registry.evictionStats("/api");
        logStats("调小后再写 1 条", afterWrite);
        assertAll("后续写入按新上限淘汰",
                () -> assertEquals(5L, afterWrite.retainedSamples()),
                () -> assertEquals(4L, afterWrite.evictedSamples()));

        // 运行期调大到 100：后续写入不再淘汰
        registry.setMaxSamplesPerApi("/api", 100L);
        for (int i = 0; i < 10; i++) {
            registry.record("/api", CallStatus.SUCCESS, 1L, 100_000L);
        }
        EvictionStats afterGrow = registry.evictionStats("/api");
        logStats("调大到 100 再写 10 条", afterGrow);
        assertAll("调大后立即按新上限保留",
                () -> assertEquals(15L, afterGrow.retainedSamples()),
                () -> assertEquals(4L, afterGrow.evictedSamples(), "淘汰数不再增长"),
                () -> assertEquals(100L, registry.maxSamplesPerApi("/api")));

        // 清除覆盖：恢复默认（不限）
        assertTrue(registry.clearMaxSamplesPerApi("/api"));
        assertFalse(registry.hasMaxSamplesOverride("/api"));
        assertEquals(0L, registry.maxSamplesPerApi("/api"), "恢复默认不限");
        assertFalse(registry.clearMaxSamplesPerApi("/api"), "重复清除返回 false");
        for (int i = 0; i < 10; i++) {
            registry.record("/api", CallStatus.SUCCESS, 1L, 100_000L);
        }
        EvictionStats afterClear = registry.evictionStats("/api");
        logStats("清除覆盖后再写 10 条", afterClear);
        assertAll("恢复默认后不再淘汰",
                () -> assertEquals(25L, afterClear.retainedSamples()),
                () -> assertEquals(4L, afterClear.evictedSamples()));
    }

    @Test
    void invalidLimitIsRejectedWithoutChangingState() {
        FixedClock clock = new FixedClock(100_000L);
        MetricsRegistry registry = new MetricsRegistry(10_000L, 1_000L, clock, 10L);
        registry.setMaxSamplesPerApi("/api", 4L);
        for (int i = 0; i < 6; i++) {
            registry.record("/api", CallStatus.SUCCESS, 1L, 100_000L);
        }
        EvictionStats before = registry.evictionStats("/api");
        logStats("拒绝前", before);

        InvalidMetricException zero = assertThrows(InvalidMetricException.class,
                () -> registry.setMaxSamplesPerApi("/api", 0L));
        InvalidMetricException negative = assertThrows(InvalidMetricException.class,
                () -> registry.setMaxSamplesPerApi("/api", -7L));
        log.info("拒绝 0 -> reason={} ; 拒绝 -7 -> reason={}", zero.reason(), negative.reason());

        EvictionStats after = registry.evictionStats("/api");
        logStats("拒绝后", after);
        assertAll("无效上限明确拒绝，已生效上限与统计都不变",
                () -> assertEquals(MetricsRegistry.REASON_INVALID_MAX_SAMPLES, zero.reason()),
                () -> assertEquals(MetricsRegistry.REASON_INVALID_MAX_SAMPLES, negative.reason()),
                () -> assertEquals(4L, registry.maxSamplesPerApi("/api"), "上限未被悄悄改掉"),
                () -> assertTrue(registry.hasMaxSamplesOverride("/api")),
                () -> assertEquals(before, after, "统计未被搞乱"));
    }

    @Test
    void limitChangeCapacityEvictionAndBackfillConcurrentlyKeepAccountingConsistent()
            throws InterruptedException {
        FixedClock clock = new FixedClock(100_000L);
        MetricsRegistry registry = new MetricsRegistry(10_000L, 1_000L, clock, 0L);
        int realtimeWrites = 3_000;
        int backfillBatches = 30;
        int backfillBatchSize = 50;

        AtomicReference<String> violation = new AtomicReference<>(null);
        AtomicLong backfillAccepted = new AtomicLong();
        CountDownLatch start = new CountDownLatch(1);

        Thread writer = new Thread(() -> {
            await(start);
            for (int i = 0; i < realtimeWrites; i++) {
                registry.record("/hot", i % 4 == 0 ? CallStatus.FAILURE : CallStatus.SUCCESS,
                        1L, 100_000L);
            }
        });
        Thread backfiller = new Thread(() -> {
            await(start);
            for (int b = 0; b < backfillBatches; b++) {
                List<CallEvent> batch = new ArrayList<>();
                for (int i = 0; i < backfillBatchSize; i++) {
                    batch.add(CallEvent.of("/hot", CallStatus.SUCCESS, 1L,
                            99_000L + (i % 10) * 100L));
                }
                BackfillReport report = registry.backfill(batch);
                backfillAccepted.addAndGet(report.accepted());
                if (report.accepted() + report.rejected() != report.received()) {
                    violation.compareAndSet(null, "回执账目不平: " + report);
                }
            }
        });
        Thread adjuster = new Thread(() -> {
            await(start);
            long[] limits = {5L, 200L, 20L, 1L, 500L};
            for (int i = 0; i < 600; i++) {
                registry.setMaxSamplesPerApi("/hot", limits[i % limits.length]);
            }
        });
        Thread reader = new Thread(() -> {
            await(start);
            for (int i = 0; i < 4_000; i++) {
                EvictionStats s = registry.evictionStats("/hot");
                if (s.retainedSamples() < 0 || s.evictedSamples() < 0
                        || (s.maxSamplesPerApi() > 0
                            && s.retainedSamples() > s.maxSamplesPerApi())) {
                    violation.compareAndSet(null, "统计错乱: " + s);
                }
                registry.snapshot("/hot").ifPresent(snap -> {
                    if (snap.callCount() < 0 || snap.successCount() < 0
                            || snap.failureCount() < 0
                            || snap.callCount() != snap.successCount() + snap.failureCount()) {
                        violation.compareAndSet(null, "快照错乱: " + snap);
                    }
                });
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

        // 收敛：停掉所有写后把上限固定为已知值，验证最终账目
        registry.setMaxSamplesPerApi("/hot", 100L);
        EvictionStats stats = registry.evictionStats("/hot");
        WindowSnapshot snap = registry.snapshot("/hot").orElseThrow();
        long totalWritten = realtimeWrites + backfillAccepted.get();
        log.info("并发：实时 {} 条 + 补报接受 {} 条，期间上限在 {{5,200,20,1,500}} 间切换 600 次",
                realtimeWrites, backfillAccepted.get());
        logStats("最终容量统计", stats);
        log.info("最终聚合 total={} success={} failure={}",
                snap.callCount(), snap.successCount(), snap.failureCount());

        assertAll("并发调整上限 + 容量淘汰 + 批量补报：账目对得上、无负值",
                () -> assertNull(violation.get(), "任何时刻读到的统计都必须自洽"),
                () -> assertEquals(backfillBatches * backfillBatchSize, backfillAccepted.get(),
                        "补报全部接受（时间戳都在窗口内）"),
                () -> assertEquals(100L, stats.retainedSamples(), "收敛后保留量等于最终上限"),
                () -> assertEquals(100L, stats.maxSamplesPerApi()),
                () -> assertEquals(totalWritten, stats.retainedSamples() + stats.evictedSamples(),
                        "总量守恒：写入总数 = 保留 + 容量淘汰（FixedClock 下无自然滚动）"),
                () -> assertEquals(stats.retainedSamples(), snap.callCount(),
                        "聚合计数与保留量一致"),
                () -> assertTrue(snap.callCount() == snap.successCount() + snap.failureCount()
                        && snap.successCount() >= 0 && snap.failureCount() >= 0,
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
