package com.github.lisama569.telemetryalertingjava.web;

/**
 * 告警规则注册请求体。
 */
public record RegisterRuleRequest(
        String ruleId,
        String api,
        String metricType,
        Double percentile,
        String operator,
        Double threshold,
        Integer pendingRequired,
        Integer recoveryRequired) {
}
