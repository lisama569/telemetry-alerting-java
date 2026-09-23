package com.github.lisama569.telemetryalertingjava.model;

/** 可被告警规则引用的窗口聚合指标。 */
public enum MetricType {
    TOTAL_COUNT,
    SUCCESS_COUNT,
    FAILURE_COUNT,
    SUCCESS_RATE,
    FAILURE_RATE,
    AVG_LATENCY_MILLIS,
    P50_LATENCY_MILLIS,
    P95_LATENCY_MILLIS,
    P99_LATENCY_MILLIS,
    MAX_LATENCY_MILLIS
}
