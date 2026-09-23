package com.github.lisama569.telemetryalertingjava.tests;

import com.github.lisama569.telemetryalertingjava.alert.AlertEvaluation;
import com.github.lisama569.telemetryalertingjava.alert.AlertEvaluator;
import com.github.lisama569.telemetryalertingjava.alert.AlertRule;
import com.github.lisama569.telemetryalertingjava.alert.ComparisonOperator;
import com.github.lisama569.telemetryalertingjava.core.FixedClock;
import com.github.lisama569.telemetryalertingjava.metrics.CallStatus;
import com.github.lisama569.telemetryalertingjava.metrics.MetricType;
import com.github.lisama569.telemetryalertingjava.metrics.MetricsRegistry;
import com.github.lisama569.telemetryalertingjava.metrics.WindowSnapshot;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发写入与并发评估测试：计数必须与真实请求数严格一致，无丢失、无重复、无负值。
 */
class ConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyTest.class);

    @Test
    void concurrentWritesProduceExactCounts() throws Exception {
        int threads = 16;
        int perThread = 5_000;
        // 每个线程写 3 个接口；成功/失败延迟交错，全部落在同一窗口内
        FixedClock clock = new FixedClock(1_000_000L);
        MetricsRegistry registry = new MetricsRegistry(600_000L, 1_000L, clock);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        long start = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            final int idx = t;
            futures.add(pool.submit(() -> {
                try {
                    barrier.await();
                    for (int i = 0; i < perThread; i++) {
                        String api = "/api-" + (i % 3);
                        CallStatus status = ((idx + i) % 2 == 0)
                                ? CallStatus.SUCCESS : CallStatus.FAILURE;
                        try {
                            registry.record(api, status, 1L + (i % 50), 1_000_000L);
                        } catch (RuntimeException ex) {
                            rejected.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            }));
        }
        assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS));
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        long grandTotal = 0;
        long grandSuccess = 0;
        long grandFailure = 0;
        for (int a = 0; a < 3; a++) {
            WindowSnapshot s = registry.snapshot("/api-" + a).orElseThrow();
            grandTotal += s.callCount();
            grandSuccess += s.successCount();
            grandFailure += s.failureCount();
            log.info("并发聚合输入: {} 线程 x {} 次 -> /api-{} total={} success={} failure={} "
                            + "failureRate={} avgLatency={} maxLatency={}",
                    threads, perThread, a, s.callCount(), s.successCount(), s.failureCount(),
                    String.format("%.3f", s.failureRate()),
                    String.format("%.2f", s.avgLatencyMillis()), s.maxLatencyMillis());
            assertEquals(s.callCount(), s.successCount() + s.failureCount(), "总数=成功+失败");
            assertTrue(s.callCount() >= 0 && s.successCount() >= 0 && s.failureCount() >= 0,
                    "计数不得为负");
        }
        log.info("并发聚合结果: 总写入期望={} 实际 total={} success={} failure={} 被拒绝={} 耗时={}ms",
                threads * perThread, grandTotal, grandSuccess, grandFailure, rejected.get(),
                elapsedMs);
        assertEquals(0, rejected.get(), "窗口内合法埋点不得被拒绝");
        assertEquals((long) threads * perThread, grandTotal, "并发计数不得丢失或重复");
        assertEquals(grandTotal, grandSuccess + grandFailure);
    }

    @Test
    void concurrentEvaluationsKeepConsistentStateMachine() throws Exception {
        // 规则：失败率 >= 0.5 即超标，连续 1 次即 FIRING，连续 1 次即 RECOVERED，
        // 便于在高并发评估下只观察状态机的线程安全与单调迁移。
        int evaluators = 8;
        int rounds = 2_000;
        FixedClock clock = new FixedClock(1_000_000L);
        MetricsRegistry registry = new MetricsRegistry(600_000L, 1_000L, clock);
        AlertEvaluator alertEvaluator = new AlertEvaluator(registry, clock);
        alertEvaluator.register(AlertRule.of("r", "/api-0", MetricType.FAILURE_RATE, 0,
                ComparisonOperator.GTE, 0.5d, 1, 1));

        // 预置确定性的初始指标：1 成功 1 失败 -> failureRate=0.5 超标
        registry.record("/api-0", CallStatus.SUCCESS, 10L, 1_000_000L);
        registry.record("/api-0", CallStatus.FAILURE, 10L, 1_000_000L);

        ExecutorService pool = Executors.newFixedThreadPool(evaluators);
        CyclicBarrier barrier = new CyclicBarrier(evaluators);
        CountDownLatch done = new CountDownLatch(evaluators);
        AtomicInteger illegal = new AtomicInteger();
        // 同一评估时刻被多线程同时评估，只能有一次真正推进，其余全部幂等返回
        long evalTime = 1_000_100L;
        List<Future<AlertEvaluation>> futures = new ArrayList<>();
        for (int i = 0; i < evaluators; i++) {
            futures.add(pool.submit(() -> {
                try {
                    barrier.await();
                    AlertEvaluation last = null;
                    for (int r = 0; r < rounds; r++) {
                        try {
                            last = alertEvaluator.evaluateAt("r", evalTime);
                        } catch (RuntimeException ex) {
                            illegal.incrementAndGet();
                        }
                    }
                    done.countDown();
                    return last;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS));
        pool.shutdown();

        AlertEvaluation first = futures.get(0).get();
        for (Future<AlertEvaluation> f : futures) {
            assertEquals(first, f.get(), "并发同刻评估必须返回同一幂等结果");
        }
        log.info("并发评估输入: {} 线程 x {} 轮，同一评估时刻 t={}；结果 state={} breached={} "
                        + "依据={}；异常次数={}",
                evaluators, rounds, evalTime, first.state(), first.breached(),
                first.reason(), illegal.get());
        assertEquals(0, illegal.get());
        assertEquals(1, first.consecutiveBreaches(), "同刻并发评估只能推进一次状态");
    }
}
