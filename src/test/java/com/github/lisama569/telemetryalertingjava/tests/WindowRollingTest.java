package com.github.lisama569.telemetryalertingjava.tests;

import com.github.lisama569.telemetryalertingjava.collector.InMemoryMetricsCollector;
import com.github.lisama569.telemetryalertingjava.model.CallRecordPoint;
import com.github.lisama569.telemetryalertingjava.model.CallStatus;
import com.github.lisama569.telemetryalertingjava.model.MetricSnapshot;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 滚动窗口：窗口外数据必须淘汰，计数随滚动递减且永不为负。 */
class WindowRollingTest {

    private static final Logger log = LoggerFactory.getLogger(WindowRollingTest.class);
    private static final String ENDPOINT = "/api/pay";

    private void record(InMemoryMetricsCollector c, long ts, long latency, CallStatus status) {
        c.record(new CallRecordPoint(ENDPOINT, ts, latency, status));
    }

    @Test
    void expiredDataMustBeEvictedAndMetricsNeverNegative() {
        long t0 = 5_000_000L;
        MutableTimeSource clock = new MutableTimeSource(t0);
        InMemoryMetricsCollector collector = new InMemoryMetricsCollector(clock);

        // 写入发生在 t0+6s 时刻，埋点时间戳为历史的 t0+1s..t0+5s
        clock.setTime(t0 + 6_000L);
        // 在 t0+1s..t0+5s 每秒写入 3 条（2 成功 1 失败），延迟 10/20/30
        for (int sec = 1; sec <= 5; sec++) {
            long ts = t0 + sec * 1_000L;
            record(collector, ts, 10L, CallStatus.SUCCESS);
            record(collector, ts, 20L, CallStatus.SUCCESS);
            record(collector, ts, 30L, CallStatus.FAILURE);
        }
        log.info("[窗口滚动] 输入: 5 个秒级时间点，每秒 3 条（2 成功/1 失败），窗口=3000ms，起点 t0={}", t0);

        clock.setTime(t0 + 6_000L);
        MetricSnapshot s1 = collector.getSnapshot(ENDPOINT, 3_000L);
        log.info("[窗口滚动] now=t0+6000 聚合: windowStart={}, total={}, success={}, failure={}, avg={}, p50={}, p95={}",
                s1.windowStartMillis(), s1.totalCount(), s1.successCount(), s1.failureCount(),
                s1.avgLatencyMillis(), s1.p50LatencyMillis(), s1.p95LatencyMillis());
        // 保留 [3000,6000]，即 sec=3,4,5 共 9 条
        assertEquals(9L, s1.totalCount(), "窗口应只包含最近 3 秒（含边界）");
        assertEquals(6L, s1.successCount());
        assertEquals(3L, s1.failureCount());
        assertEquals(20.0d, s1.avgLatencyMillis(), 0.0001d);

        clock.setTime(t0 + 7_000L);
        MetricSnapshot s2 = collector.getSnapshot(ENDPOINT, 3_000L);
        log.info("[窗口滚动] now=t0+7000 聚合: windowStart={}, total={}, success={}, failure={}",
                s2.windowStartMillis(), s2.totalCount(), s2.successCount(), s2.failureCount());
        // 保留 [4000,7000]，即 sec=4,5 共 6 条
        assertEquals(6L, s2.totalCount(), "滚动 1 秒后 sec=3 的 3 条必须被淘汰");
        assertEquals(4L, s2.successCount());
        assertEquals(2L, s2.failureCount());

        // 全部过期后必须为零值快照，无负值
        clock.setTime(t0 + 60_000L);
        MetricSnapshot s3 = collector.getSnapshot(ENDPOINT, 3_000L);
        log.info("[窗口滚动] now=t0+60000 聚合: total={}, failureRate={}, maxLatency={}, avg={}",
                s3.totalCount(), s3.failureRate(), s3.maxLatencyMillis(), s3.avgLatencyMillis());
        assertEquals(0L, s3.totalCount());
        assertEquals(0L, s3.successCount());
        assertEquals(0L, s3.failureCount());
        assertEquals(0.0d, s3.failureRate());
        assertEquals(0L, s3.maxLatencyMillis());

        assertTrue(allNonNegative(s1) && allNonNegative(s2) && allNonNegative(s3),
                "所有指标在任何时刻不得为负");
        log.info("[窗口滚动] 判定依据: 各阶段计数 9→6→0，成功/失败拆分精确，零值快照无负值");
    }

    @Test
    void differentWindowLengthsOverSameData() {
        long t0 = 9_000_000L;
        MutableTimeSource clock = new MutableTimeSource(t0 + 2_000L);
        InMemoryMetricsCollector collector = new InMemoryMetricsCollector(clock);
        record(collector, t0 + 500L, 5L, CallStatus.SUCCESS);
        record(collector, t0 + 1_500L, 15L, CallStatus.FAILURE);

        MetricSnapshot wide = collector.getSnapshot(ENDPOINT, 5_000L);
        MetricSnapshot narrow = collector.getSnapshot(ENDPOINT, 1_000L);
        log.info("[多窗口] 输入: ts=t0+500(成功) 与 ts=t0+1500(失败), now=t0+2000；5s窗口 total={}, 1s窗口 total={}",
                wide.totalCount(), narrow.totalCount());
        assertEquals(2L, wide.totalCount());
        // (1000,2000] 只含 t0+1500
        assertEquals(1L, narrow.totalCount());
        assertEquals(1L, narrow.failureCount());
        log.info("[多窗口] 判定依据: 宽窗口包含 2 条，窄窗口仅保留 t0+1500 的 1 条失败（窗口 [1000,2000]）");
    }

    private static boolean allNonNegative(MetricSnapshot s) {
        return s.totalCount() >= 0 && s.successCount() >= 0 && s.failureCount() >= 0
                && s.successRate() >= 0.0d && s.failureRate() >= 0.0d
                && s.avgLatencyMillis() >= 0.0d && s.minLatencyMillis() >= 0L
                && s.maxLatencyMillis() >= 0L && s.p50LatencyMillis() >= 0L
                && s.p95LatencyMillis() >= 0L && s.p99LatencyMillis() >= 0L;
    }
}
