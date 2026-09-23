package com.github.lisama569.telemetryalertingjava.metrics;

import java.util.List;

/**
 * 某接口在当前滚动窗口内的聚合结果。
 *
 * <p>口径说明：所有计数均为左闭右开窗口 {@code [windowStart, windowEnd)} 内的累计值；
 * 比率取值范围 0~1；无调用时成功率/失败率定义为 0（而非 NaN），避免下游误判。
 *
 * @param sortedLatenciesMillis 窗口内全部延迟样本（升序拷贝），用于分位数计算
 */
public record WindowSnapshot(
        String api,
        long windowStartMillis,
        long windowEndMillis,
        long callCount,
        long successCount,
        long failureCount,
        double successRate,
        double failureRate,
        double avgLatencyMillis,
        long maxLatencyMillis,
        List<Long> sortedLatenciesMillis) {

    /**
     * 延迟分位数（percentile 范围 0~100），采用最近秩法（nearest-rank）。
     * 窗口内无样本时返回 0，任何情况下都不会出现负值。
     */
    public long latencyPercentile(double percentile) {
        if (sortedLatenciesMillis.isEmpty()) {
            return 0L;
        }
        double clamped = Math.max(0d, Math.min(100d, percentile));
        // rank = ceil(p/100 * n)，取值区间 [1,n]
        long rank = (long) Math.ceil((clamped / 100d) * sortedLatenciesMillis.size());
        rank = Math.max(1L, Math.min((long) sortedLatenciesMillis.size(), rank));
        return sortedLatenciesMillis.get((int) rank - 1);
    }

    /**
     * 按指标口径取值。percentile 仅对 {@link MetricType#LATENCY_PERCENTILE} 生效。
     */
    public double value(MetricType type, double percentile) {
        return switch (type) {
            case CALL_COUNT -> callCount;
            case FAILURE_COUNT -> failureCount;
            case SUCCESS_RATE -> successRate;
            case FAILURE_RATE -> failureRate;
            case AVG_LATENCY_MS -> avgLatencyMillis;
            case MAX_LATENCY_MS -> maxLatencyMillis;
            case LATENCY_PERCENTILE -> latencyPercentile(percentile);
        };
    }
}
