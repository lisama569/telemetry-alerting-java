package com.github.lisama569.telemetryalertingjava.tests;

import com.github.lisama569.telemetryalertingjava.core.FixedClock;
import com.github.lisama569.telemetryalertingjava.metrics.BackfillReport;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 历史补报测试：乱序补报与实时写入混合、回执口径、非法条目容错。
 */
class BackfillTest {

    private static final Logger log = LoggerFactory.getLogger(BackfillTest.class);

    private MetricsRegistry newRegistry(FixedClock clock) {
        // 10s 窗口，1s 桶
        return new MetricsRegistry(10_000L, 1_000L, clock);
    }

    private void logSnapshot(String title, WindowSnapshot s) {
        log.info("{} | api={} window=[{},{}) total={} success={} failure={} avgLatency={} "
                        + "maxLatency={} p50={} p95={}",
                title, s.api(), s.windowStartMillis(), s.windowEndMillis(),
                s.callCount(), s.successCount(), s.failureCount(),
                String.format("%.2f", s.avgLatencyMillis()), s.maxLatencyMillis(),
                s.latencyPercentile(50), s.latencyPercentile(95));
    }

    private void logReport(String title, BackfillReport r) {
        log.info("{} | received={} accepted={} rejected={} slots={} rejections={}",
                title, r.received(), r.accepted(), r.rejected(), r.slots(), r.rejections());
    }

    @Test
    void outOfOrderBackfillConvergesWithRealtimeIngestion() {
        FixedClock clock = new FixedClock(100_000L);
        // 同一批事件：分布在 [91s, 100s] 的 10 个桶里，成功失败与延迟交错
        List<CallEvent> events = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long ts = 91_000L + i * 450L; // 91000 ~ 99550，乱序后跨多个桶
            CallStatus status = i % 4 == 3 ? CallStatus.FAILURE : CallStatus.SUCCESS;
            events.add(CallEvent.of("/order", status, 10L + i * 5L, ts));
        }
        log.info("输入事件 {} 条，时间范围 [{}, {}]，其中失败 {} 条", events.size(),
                events.get(0).timestampMillis(),
                events.get(events.size() - 1).timestampMillis(),
                events.stream().filter(e -> e.status() == CallStatus.FAILURE).count());

        // 对照组 A：全部按时间升序实时写入
        MetricsRegistry realtime = newRegistry(clock);
        for (CallEvent e : events) {
            realtime.record(e);
        }

        // 实验组 B：最近 5 条实时写入，其余 15 条打乱顺序（降序）走补报
        MetricsRegistry mixed = newRegistry(clock);
        List<CallEvent> tail = events.subList(events.size() - 5, events.size());
        for (CallEvent e : tail) {
            mixed.record(e);
        }
        List<CallEvent> shuffled = new ArrayList<>(events.subList(0, events.size() - 5));
        Collections.reverse(shuffled); // 固定可复现的乱序：完全倒置
        log.info("补报输入（乱序 {} 条）: {}", shuffled.size(), shuffled);
        BackfillReport report = mixed.backfill(shuffled);
        logReport("补报回执", report);

        assertEquals(shuffled.size(), report.accepted(), "乱序补报必须全部被接受");
        assertTrue(report.rejections().isEmpty());

        WindowSnapshot expected = realtime.snapshot("/order").orElseThrow();
        WindowSnapshot actual = mixed.snapshot("/order").orElseThrow();
        logSnapshot("全量实时写入聚合", expected);
        logSnapshot("实时+乱序补报聚合", actual);

        assertAll("补报后聚合与实时写入一致",
                () -> assertEquals(expected.callCount(), actual.callCount(), "调用次数"),
                () -> assertEquals(expected.successCount(), actual.successCount(), "成功次数"),
                () -> assertEquals(expected.failureCount(), actual.failureCount(), "失败次数"),
                () -> assertEquals(expected.avgLatencyMillis(), actual.avgLatencyMillis(), 1e-9,
                        "平均延迟"),
                () -> assertEquals(expected.maxLatencyMillis(), actual.maxLatencyMillis(),
                        "最大延迟"),
                () -> assertEquals(expected.sortedLatenciesMillis(),
                        actual.sortedLatenciesMillis(), "延迟分布（升序样本）"),
                () -> assertEquals(expected.latencyPercentile(95), actual.latencyPercentile(95),
                        "p95 延迟"));
    }

    @Test
    void backfillReceiptReportsSlotsAndRejections() {
        FixedClock clock = new FixedClock(100_000L);
        MetricsRegistry registry = newRegistry(clock);

        List<CallEvent> batch = new ArrayList<>();
        // 合法：3 条落在桶 95000，2 条落在桶 98000，1 条落在桶 100000（当前桶）
        batch.add(CallEvent.of("/pay", CallStatus.SUCCESS, 10L, 95_100L));
        batch.add(CallEvent.of("/pay", CallStatus.FAILURE, 20L, 95_900L));
        batch.add(CallEvent.of("/pay", CallStatus.SUCCESS, 30L, 95_500L));
        batch.add(CallEvent.of("/pay", CallStatus.SUCCESS, 40L, 98_000L));
        batch.add(CallEvent.of("/pay", CallStatus.FAILURE, 50L, 98_999L));
        batch.add(CallEvent.of("/pay", CallStatus.SUCCESS, 60L, 100_000L));
        // 非法：过旧（idx6）、未来（idx7）、负延迟（idx8）、空接口名（idx9）、null 事件（idx10）
        batch.add(CallEvent.of("/pay", CallStatus.SUCCESS, 1L, 89_999L));
        batch.add(CallEvent.of("/pay", CallStatus.SUCCESS, 1L, 100_001L));
        batch.add(CallEvent.of("/pay", CallStatus.SUCCESS, -5L, 96_000L));
        batch.add(CallEvent.of("  ", CallStatus.SUCCESS, 1L, 96_000L));
        batch.add(null);
        log.info("补报输入 {} 条：6 合法 + 5 非法（过旧/未来/负延迟/空接口/null）", batch.size());

        BackfillReport report = registry.backfill(batch);
        logReport("补报回执", report);

        assertAll("回执口径",
                () -> assertEquals(11, report.received(), "收到总数"),
                () -> assertEquals(6, report.accepted(), "接受数"),
                () -> assertEquals(5, report.rejected(), "拒绝数"),
                () -> assertEquals(MetricsRegistry.REASON_TIMESTAMP_TOO_OLD,
                        report.rejections().get(0).reason(), "idx6 过旧"),
                () -> assertEquals(6, report.rejections().get(0).index()),
                () -> assertEquals(MetricsRegistry.REASON_TIMESTAMP_IN_FUTURE,
                        report.rejections().get(1).reason(), "idx7 未来"),
                () -> assertEquals(MetricsRegistry.REASON_NEGATIVE_LATENCY,
                        report.rejections().get(2).reason(), "idx8 负延迟"),
                () -> assertEquals(MetricsRegistry.REASON_BLANK_API,
                        report.rejections().get(3).reason(), "idx9 空接口名"),
                () -> assertEquals(MetricsRegistry.REASON_NULL_EVENT,
                        report.rejections().get(4).reason(), "idx10 null 事件"));

        assertAll("落点明细（接口, 桶起点, 条数）",
                () -> assertEquals(3, report.slots().size(), "3 个桶收到数据"),
                () -> assertEquals(new BackfillReport.Slot("/pay", 95_000L, 3L),
                        report.slots().get(0)),
                () -> assertEquals(new BackfillReport.Slot("/pay", 98_000L, 2L),
                        report.slots().get(1)),
                () -> assertEquals(new BackfillReport.Slot("/pay", 100_000L, 1L),
                        report.slots().get(2)));

        WindowSnapshot s = registry.snapshot("/pay").orElseThrow();
        logSnapshot("补报后聚合", s);
        assertAll("被拒绝的条目不得计入",
                () -> assertEquals(6L, s.callCount()),
                () -> assertEquals(4L, s.successCount()),
                () -> assertEquals(2L, s.failureCount()),
                () -> assertEquals(35d, s.avgLatencyMillis(), 1e-9, "(10+20+30+40+50+60)/6"));
    }

    @Test
    void backfillCoexistsWithRealtimeWritesWithoutLoss() throws InterruptedException {
        FixedClock clock = new FixedClock(100_000L);
        MetricsRegistry registry = newRegistry(clock);
        int realtimeCount = 2_000;
        int backfillCount = 2_000;

        // 补报事件：均匀分布在窗口内 10 个桶，批内顺序打乱（倒序）
        List<CallEvent> batch = new ArrayList<>();
        for (int i = 0; i < backfillCount; i++) {
            long ts = 91_000L + (i % 10) * 1_000L;
            batch.add(CallEvent.of("/api", i % 2 == 0 ? CallStatus.SUCCESS : CallStatus.FAILURE,
                    i % 100L, ts));
        }
        Collections.reverse(batch);

        CountDownLatch start = new CountDownLatch(1);
        Thread backfiller = new Thread(() -> {
            await(start);
            // 分批补报，模拟旁路日志批量捞回
            for (int from = 0; from < batch.size(); from += 200) {
                registry.backfill(batch.subList(from, Math.min(from + 200, batch.size())));
            }
        });
        Thread realtimeWriter = new Thread(() -> {
            await(start);
            for (int i = 0; i < realtimeCount; i++) {
                registry.record("/api", CallStatus.SUCCESS, 5L, 100_000L);
            }
        });
        backfiller.start();
        realtimeWriter.start();
        start.countDown();
        backfiller.join(10_000);
        realtimeWriter.join(10_000);
        assertAll("写入线程必须在限定时间内结束",
                () -> assertFalse(backfiller.isAlive()),
                () -> assertFalse(realtimeWriter.isAlive()));

        WindowSnapshot s = registry.snapshot("/api").orElseThrow();
        logSnapshot("并发实时+补报后聚合", s);
        log.info("判定依据：实时写入 {} 条 + 补报 {} 条，期望总数 {}，实际 {}",
                realtimeCount, backfillCount, realtimeCount + backfillCount, s.callCount());
        assertAll("补报与实时并存不丢失不重复",
                () -> assertEquals((long) realtimeCount + backfillCount, s.callCount()),
                () -> assertEquals((long) realtimeCount + backfillCount / 2, s.successCount()),
                () -> assertEquals((long) backfillCount / 2, s.failureCount()));
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
