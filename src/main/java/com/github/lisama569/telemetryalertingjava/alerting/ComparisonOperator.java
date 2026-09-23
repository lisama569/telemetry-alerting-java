package com.github.lisama569.telemetryalertingjava.alerting;

/** 告警阈值比较算子。 */
public enum ComparisonOperator {
    GREATER_THAN,
    GREATER_THAN_OR_EQUAL,
    LESS_THAN,
    LESS_THAN_OR_EQUAL;

    /** 判断指标值是否满足阈值条件（NaN 一律不满足）。 */
    public boolean matches(double metricValue, double threshold) {
        if (Double.isNaN(metricValue) || Double.isInfinite(metricValue)) {
            return false;
        }
        return switch (this) {
            case GREATER_THAN -> metricValue > threshold;
            case GREATER_THAN_OR_EQUAL -> metricValue >= threshold;
            case LESS_THAN -> metricValue < threshold;
            case LESS_THAN_OR_EQUAL -> metricValue <= threshold;
        };
    }
}
