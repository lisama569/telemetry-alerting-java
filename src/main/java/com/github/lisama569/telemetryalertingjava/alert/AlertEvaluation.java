package com.github.lisama569.telemetryalertingjava.alert;

/**
 * 单次规则评估结果，记录判定依据，保证可追溯。
 */
public record AlertEvaluation(
        String ruleId,
        long evaluationMillis,
        AlertState state,
        boolean breached,
        double metricValue,
        int consecutiveBreaches,
        int consecutiveRecoveries,
        String reason) {
}
