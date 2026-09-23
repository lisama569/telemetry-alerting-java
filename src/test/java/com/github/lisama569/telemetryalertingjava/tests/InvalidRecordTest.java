package com.github.lisama569.telemetryalertingjava.tests;

import com.github.lisama569.telemetryalertingjava.collector.InMemoryMetricsCollector;
import com.github.lisama569.telemetryalertingjava.exception.InvalidRecordException;
import com.github.lisama569.telemetryalertingjava.model.CallRecordPoint;
import com.github.lisama569.telemetryalertingjava.model.CallStatus;
import com.github.lisama569.telemetryalertingjava.model.MetricSnapshot;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 非法埋点必须被拒绝、原因可区分，且不得静默计入任何统计。 */
class InvalidRecordTest {

    private static final Logger log = LoggerFactory.getLogger(InvalidRecordTest.class);
    private static final String EP = "/api/legal";
    private final long now = 8_000_000L;

    private void assertRejected(InMemoryMetricsCollector collector, CallRecordPoint bad, String expectedCode) {
        InvalidRecordException ex = assertThrows(InvalidRecordException.class, () -> collector.record(bad),
                "非法埋点必须被拒绝: " + expectedCode);
        log.info("[非法埋点] 输入={}, 拒绝原因码={}, message={}", describe(bad), ex.getReasonCode(), ex.getMessage());
        assertEquals(expectedCode, ex.getReasonCode(), "原因码必须可区分");
    }

    private static String describe(CallRecordPoint p) {
        if (p == null) {
            return "null";
        }
        return "endpoint='" + p.endpoint() + "', ts=" + p.timestampMillis()
                + ", latency=" + p.latencyMillis() + ", status=" + p.status();
    }

    @Test
    void invalidRecordsAreRejectedWithDistinctReasonsAndNotCounted() {
        MutableTimeSource clock = new MutableTimeSource(now);
        InMemoryMetricsCollector collector = new InMemoryMetricsCollector(clock);

        // 先写入一条合法埋点
        collector.record(new CallRecordPoint(EP, now - 100L, 50L, CallStatus.SUCCESS));

        assertRejected(collector, null, "INVALID_RECORD_NULL");
        assertRejected(collector, new CallRecordPoint("  ", now - 10L, 1L, CallStatus.SUCCESS),
                "INVALID_RECORD_ENDPOINT_BLANK");
        assertRejected(collector, new CallRecordPoint(EP, 0L, 1L, CallStatus.SUCCESS),
                "INVALID_RECORD_TIMESTAMP_MISSING");
        assertRejected(collector, new CallRecordPoint(EP, now + 60_000L, 1L, CallStatus.SUCCESS),
                "INVALID_RECORD_TIMESTAMP_FUTURE");
        assertRejected(collector, new CallRecordPoint(EP, now - 10L, -5L, CallStatus.SUCCESS),
                "INVALID_RECORD_LATENCY_NEGATIVE");
        assertRejected(collector, new CallRecordPoint(EP, now - 10L, 1_000L, CallStatus.SUCCESS),
                "INVALID_RECORD_LATENCY_OUT_OF_RANGE");
        assertRejected(collector, new CallRecordPoint(EP, now - 10L, 1L, null),
                "INVALID_RECORD_STATUS_MISSING");

        MetricSnapshot snapshot = collector.getSnapshot(EP, 60_000L);
        log.info("[非法埋点] 拒绝 7 类非法输入后的聚合结果: total={}, success={}, failure={}（仅初始 1 条合法埋点）",
                snapshot.totalCount(), snapshot.successCount(), snapshot.failureCount());
        assertEquals(1L, snapshot.totalCount(), "非法埋点不得计入统计");
        assertEquals(1L, snapshot.successCount());
        assertEquals(0L, snapshot.failureCount());
        log.info("[非法埋点] 判定依据: total 保持为 1，证明 7 类非法输入均未静默计入");
    }

    @Test
    void invalidQueryArgumentsRejected() {
        MutableTimeSource clock = new MutableTimeSource(now);
        InMemoryMetricsCollector collector = new InMemoryMetricsCollector(clock);
        assertThrows(RuntimeException.class, () -> collector.getSnapshot(" ", 1_000L));
        assertThrows(RuntimeException.class, () -> collector.getSnapshot(EP, 0L));
        assertThrows(RuntimeException.class, () -> collector.getSnapshot(EP, -1L));
        log.info("[非法埋点] 非法查询参数（空白接口、非正窗口）均被拒绝");
    }
}
