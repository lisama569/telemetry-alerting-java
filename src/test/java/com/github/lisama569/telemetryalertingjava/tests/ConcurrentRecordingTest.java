package com.github.lisama569.telemetryalertingjava.tests;

import com.github.lisama569.telemetryalertingjava.collector.InMemoryMetricsCollector;
import com.github.lisama569.telemetryalertingjava.model.CallRecordPoint;
import com.github.lisama569.telemetryalertingjava.model.CallStatus;
import com.github.lisama569.telemetryalertingjava.model.MetricSnapshot;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 并发写入下计数不得丢失、重复计入，成功/失败拆分必须精确。 */
class ConcurrentRecordingTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentRecordingTest.class);

    private static final int THREADS = 16;
    private static final int PER_THREAD = 1_000;
    private static final String ENDPOINT = "/api/order";

    @Test
    void concurrentRecordingCountsMustMatchRealRequests() throws InterruptedException {
        long base = 1_000_000L;
        MutableTimeSource clock = new MutableTimeSource(base + 10_000L);
        InMemoryMetricsCollector collector = new InMemoryMetricsCollector(clock);

        int expectedSuccess = 0;
        int expectedFailure = 0;
        List<CallRecordPoint> allPoints = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            for (int i = 0; i < PER_THREAD; i++) {
                boolean success = (t + i) % 3 != 0;
                if (success) {
                    expectedSuccess++;
                } else {
                    expectedFailure++;
                }
                // 时间戳落在窗口内且不晚于当前时钟
                long ts = base + 1_000L + (t * 7L + i * 13L) % 8_000L;
                allPoints.add(new CallRecordPoint(ENDPOINT, ts, (i % 50) + 1L,
                        success ? CallStatus.SUCCESS : CallStatus.FAILURE));
            }
        }
        int expectedTotal = THREADS * PER_THREAD;
        log.info("[并发写入] 输入: threads={}, perThread={}, expectedTotal={}, expectedSuccess={}, expectedFailure={}",
                THREADS, PER_THREAD, expectedTotal, expectedSuccess, expectedFailure);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        for (int t = 0; t < THREADS; t++) {
            final int from = t * PER_THREAD;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < PER_THREAD; i++) {
                        collector.record(allPoints.get(from + i));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发写入应在超时前完成");
        pool.shutdown();

        MetricSnapshot snapshot = collector.getSnapshot(ENDPOINT, 60_000L);
        log.info("[并发写入] 聚合结果: total={}, success={}, failure={}, successRate={}, failureRate={}, avgLatency={}, p95={}",
                snapshot.totalCount(), snapshot.successCount(), snapshot.failureCount(),
                snapshot.successRate(), snapshot.failureRate(),
                snapshot.avgLatencyMillis(), snapshot.p95LatencyMillis());

        assertEquals(expectedTotal, snapshot.totalCount(), "总调用数必须与真实请求数一致，不得丢失或重复");
        assertEquals(expectedSuccess, snapshot.successCount(), "成功数必须精确");
        assertEquals(expectedFailure, snapshot.failureCount(), "失败数必须精确");
        assertEquals(snapshot.totalCount(),
                snapshot.successCount() + snapshot.failureCount(),
                "total 必须等于 success + failure");
        assertTrue(snapshot.failureRate() >= 0.0d && snapshot.successRate() >= 0.0d,
                "比率不得为负");

        // 第二次读取结果必须稳定（幂等读）
        MetricSnapshot again = collector.getSnapshot(ENDPOINT, 60_000L);
        assertEquals(snapshot.totalCount(), again.totalCount());
        log.info("[并发写入] 判定依据: total==expected={}, success+failure==total={}, repeatReadTotal={}",
                snapshot.totalCount() == expectedTotal,
                snapshot.successCount() + snapshot.failureCount() == snapshot.totalCount(),
                again.totalCount());
    }
}
