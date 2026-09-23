package com.github.lisama569.telemetryalertingjava.metrics;

/**
 * 可用于告警规则的窗口聚合指标口径。
 */
public enum MetricType {
    /** 窗口内调用总次数 */
    CALL_COUNT,
    /** 窗口内失败次数 */
    FAILURE_COUNT,
    /** 窗口内成功率（0~1） */
    SUCCESS_RATE,
    /** 窗口内失败率（0~1） */
    FAILURE_RATE,
    /** 窗口内平均延迟（毫秒） */
    AVG_LATENCY_MS,
    /** 窗口内最大延迟（毫秒） */
    MAX_LATENCY_MS,
    /** 窗口内延迟的 P 分位数（毫秒），P 由规则 percentile 字段给出 */
    LATENCY_PERCENTILE
}
