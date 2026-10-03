package com.github.lisama569.telemetryalertingjava.tests;

import com.github.lisama569.telemetryalertingjava.core.FixedClock;
import com.github.lisama569.telemetryalertingjava.metrics.BackfillReceipt;
import com.github.lisama569.telemetryalertingjava.metrics.CallEvent;
import com.github.lisama569.telemetryalertingjava.metrics.CallStatus;
import com.github.lisama569.telemetryalertingjava.metrics.MetricsRegistry;
import com.github.lisama569.telemetryalertingjava.metrics.WindowSnapshot;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 历史补报测试：乱序回填、与实时写入一致性、回执口径、补报与实时并发。
 */
class BackfillTest {

    private static final Logger log = LoggerFactory.getLogger(BackfillTest.class);

    /** 10s 窗口、1s 桶、60s 保留期：补报可以补到 60s 前的数据。 */
    private MetricsRegistry newRegistry(FixedClock clock) {
        return new MetricsRegistry(10_000L, 1_000L, 60_000L, 0L, clock);
    }

    private void logSnapshot(String title, WindowSnapshot s) {
        log.info("{} | api={} window=[{},{}) total={} success={} failure={} avgLatency={} "
                        + "maxLatency={} p50={} p95={}",
                title, s.api(), s.windowStartMillis(), s.windowEndMillis(),
                s.callCount(), s.successCount(), s.failureCount(),
                String.format("%.2f", s.avgLatencyMillis()), s.maxLatencyMillis(),
                s.latencyPercentile(50), s.latencyPercentile(95));
    }

    @Test
    void backfilledOutOfOrderEventsMatchRealtimeReference() {
        // 输入：20 条事件，t = 45s + i*500ms，交替成功/失败，延迟 10+i
        List<CallEvent> all = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            all.add(CallEvent.of("/order", i % 3 == 0 ? CallStatus.FAILURE : CallStatus.SUCCESS,
                    10L + i, 45_000L + i * 500L));
        }
        log.info("输入事件 20 条：t∈[45000,54500]，前 10 条（t<50s）将作为乱序补报，"
                + "后 10 条实时写入");

        // 参照组：所有事件按发生时刻就近实时写入（时钟随事件推进）
        FixedClock refClock = new FixedClock(45_000L);
        MetricsRegistry reference = newRegistry(refClock);
        for (CallEvent e : all) {
            refClock.setMillis(e.timestampMillis());
            reference.record(e);
        }
        refClock.setMillis(55_000L);
        WindowSnapshot expected = reference.snapshot("/order").orElseThrow();
        logSnapshot("参照组（全程实时写入）", expected);

        // 实验组：后 10 条实时写入，前 10 条倒序补报（乱序且早于已见数据）
        FixedClock clock = new FixedClock(55_000L);
        MetricsRegistry target = newRegistry(clock);
        for (int i = 10; i < 20; i++) {
            CallEvent e = all.get(i);
            target.record(e.api(), e.status(), e.latencyMillis(), e.timestampMillis());
        }
        List<CallEvent> backfillBatch = new ArrayList<>(all.subList(0, 10));
        Collections.reverse(backfillBatch);
        log.info("补报批次（倒序）: {}", backfillBatch);
        BackfillReceipt receipt = target.backfill(backfillBatch);
        log.info("补报回执: accepted={} rejected={} 落桶分布={}",
                receipt.acceptedCount(), receipt.rejectedCount(), receipt.acceptedByBucket());

        assertEquals(10, receipt.acceptedCount(), "10 条补报全部接受");
        assertEquals(0, receipt.rejectedCount());
        // 落桶：t=45s..49.5s 以 500ms 间隔落在 5 个 1s 桶，每桶 2 条
        assertEquals(5, receipt.acceptedByBucket().get("/order").size());
        assertTrue(receipt.acceptedByBucket().get("/order").values().stream()
                .allMatch(n -> n == 2), "每桶恰好 2 条");

        WindowSnapshot actual = target.snapshot("/order").orElseThrow();
        logSnapshot("实验组（实时+乱序补报）", actual);
        assertAll("补报后聚合与实时参照一致",
                () -> assertEquals(expected.callCount(), actual.callCount()),
                () -> assertEquals(expected.successCount(), actual.successCount()),
                () -> assertEquals(expected.failureCount(), actual.failureCount()),
                () -> assertEquals(expected.avgLatencyMillis(), actual.avgLatencyMillis(), 1e-9),
                () -> assertEquals(expected.maxLatencyMillis(), actual.maxLatencyMillis()),
                () -> assertEquals(expected.sortedLatenciesMillis(), actual.sortedLatenciesMillis(),
                        "延迟分布不能多也不能少"),
                () -> assertEquals(expected.latencyPercentile(95), actual.latencyPercentile(95)));
    }

    @Test
    void backfillReceiptRejectsInvalidEventsWithReasons() {
        FixedClock clock = new FixedClock(100_000L);
        MetricsRegistry registry = newRegistry(clock);

        List<CallEvent> batch = new ArrayList<>();
        batch.add(CallEvent.of("/a", CallStatus.SUCCESS, 10L, 95_000L));        // 0 正常
        batch.add(CallEvent.of("/a", CallStatus.FAILURE, 20L, 100_001L));       // 1 未来时间戳
        batch.add(CallEvent.of("/a", CallStatus.SUCCESS, 30L, 39_999L));        // 2 超出保留期
        batch.add(CallEvent.of("/a", null, 10L, 95_000L));                      // 3 状态缺失
        batch.add(CallEvent.of("/a", CallStatus.SUCCESS, -5L, 95_000L));        // 4 负延迟
        batch.add(null);                                                        // 5 空事件
        batch.add(CallEvent.of("  ", CallStatus.SUCCESS, 10L, 95_000L));        // 6 空接口名
        batch.add(CallEvent.of("/a", CallStatus.SUCCESS, 40L, 90_000L));        // 7 正常（更老但保留期内）
        log.info("补报批次 8 条：2 条正常，6 条各类非法");

        BackfillReceipt receipt = registry.backfill(batch);
        log.info("回执: accepted={} rejected={}", receipt.acceptedCount(), receipt.rejectedCount());
        receipt.rejections().forEach(r ->
                log.info("  拒绝明细: index={} api={} reason={} msg={}",
                        r.index(), r.api(), r.reason(), r.message()));
        log.info("  落桶分布: {}", receipt.acceptedByBucket());

        assertAll("回执口径",
                () -> assertEquals(2, receipt.acceptedCount()),
                () -> assertEquals(6, receipt.rejectedCount()),
                () -> assertEquals(MetricsRegistry.REASON_TIMESTAMP_IN_FUTURE,
                        receipt.rejections().get(0).reason()),
                () -> assertEquals(1, receipt.rejections().get(0).index(), "下标定位到原批次"),
                () -> assertEquals(MetricsRegistry.REASON_TIMESTAMP_TOO_OLD,
                        receipt.rejections().get(1).reason()),
                () -> assertEquals(MetricsRegistry.REASON_NULL_STATUS,
                        receipt.rejections().get(2).reason()),
                () -> assertEquals(MetricsRegistry.REASON_NEGATIVE_LATENCY,
                        receipt.rejections().get(3).reason()),
                () -> assertEquals(MetricsRegistry.REASON_NULL_EVENT,
                        receipt.rejections().get(4).reason()),
                () -> assertEquals(MetricsRegistry.REASON_BLANK_API,
                        receipt.rejections().get(5).reason()),
                () -> assertEquals(Map.of(95_000L, 1, 90_000L, 1),
                        receipt.acceptedByBucket().get("/a"), "落桶分布精确到桶"));

        WindowSnapshot s = registry.snapshot("/a").orElseThrow();
        logSnapshot("补报后聚合", s);
        assertEquals(2L, s.callCount(), "被拒绝的事件不得计入");
    }

    @Test
    void backfillInterleavedWithRealtimeWritesLosesNothing() throws InterruptedException {
        FixedClock clock = new FixedClock(100_000L);
        MetricsRegistry registry = newRegistry(clock);
        int realtimeThreads = 4;
        int realtimePerThread = 1_000;
        int backfillTotal = 2_000;
        log.info("并发输入: {} 线程 x {} 条实时写入 + {} 条乱序补报（t∈[95s,100s]）",
                realtimeThreads, realtimePerThread, backfillTotal);

        ExecutorService pool = Executors.newFixedThreadPool(realtimeThreads + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int t = 0; t < realtimeThreads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < realtimePerThread; i++) {
                        registry.record("/mix", CallStatus.SUCCESS, 10L, clock.nowMillis());
                    }
                } catch (Throwable ex) {
                    failure.compareAndSet(null, ex);
                }
            });
        }
        pool.submit(() -> {
            try {
                start.await();
                // 分批补报，批内乱序
                List<CallEvent> batch = new ArrayList<>();
                for (int i = 0; i < backfillTotal; i++) {
                    batch.add(CallEvent.of("/mix", i % 2 == 0 ? CallStatus.SUCCESS
                            : CallStatus.FAILURE, 20L, 95_000L + (i * 7919L) % 5_000L));
                    if (batch.size() == 100) {
                        Collections.shuffle(batch);
                        registry.backfill(batch);
                        batch.clear();
                    }
                }
            } catch (Throwable ex) {
                failure.compareAndSet(null, ex);
            }
        });
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "并发任务应在限时内完成");
        assertNull(failure.get(), "补报与实时写入并存不得互相干扰");

        WindowSnapshot s = registry.snapshot("/mix").orElseThrow();
        long expectedTotal = (long) realtimeThreads * realtimePerThread + backfillTotal;
        logSnapshot("混合写入后聚合(期望 total=" + expectedTotal + ")", s);
        log.info("判定依据: total={} == 实时 {} + 补报 {}; retained={}",
                s.callCount(), (long) realtimeThreads * realtimePerThread, backfillTotal,
                registry.retainedCount("/mix"));
        assertAll("并发混合口径",
                () -> assertEquals(expectedTotal, s.callCount(), "不丢失、不重复"),
                () -> assertEquals(expectedTotal, registry.retainedCount("/mix")),
                () -> assertEquals(s.callCount(), s.successCount() + s.failureCount()),
                () -> assertEquals(backfillTotal / 2, s.failureCount(), "补报里一半是失败"),
                () -> assertTrue(s.callCount() >= 0 && registry.evictedCount("/mix") >= 0));
    }
}
