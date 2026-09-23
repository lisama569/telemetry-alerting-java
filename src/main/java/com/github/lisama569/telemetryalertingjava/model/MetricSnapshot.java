package com.github.lisama569.telemetryalertingjava.model;

/**
 * 某接口在一个滚动时间窗口内的聚合快照。
 * 不变式：totalCount = successCount + failureCount；所有字段非负；
 * 当 totalCount = 0 时，比率与延迟统计均为 0。
 */
public record MetricSnapshot(
        String endpoint,
        long windowMillis,
        long windowStartMillis,
        long windowEndMillis,
        long totalCount,
        long successCount,
        long failureCount,
        double successRate,
        double failureRate,
        double avgLatencyMillis,
        long minLatencyMillis,
        long maxLatencyMillis,
        long p50LatencyMillis,
        long p95LatencyMillis,
        long p99LatencyMillis) {
}
