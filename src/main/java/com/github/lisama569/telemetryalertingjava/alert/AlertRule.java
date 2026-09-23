package com.github.lisama569.telemetryalertingjava.alert;

import com.github.lisama569.telemetryalertingjava.metrics.MetricType;

/**
 * 基于单个接口单一指标的告警规则。
 *
 * <ul>
 *   <li>{@code pendingRequired}：指标连续超标多少次才从待确认进入触发中，抑制短暂抖动；</li>
 *   <li>{@code recoveryRequired}：触发中后连续达标多少次才进入已恢复，恢复需稳定；</li>
 *   <li>{@code percentile}：仅 {@link MetricType#LATENCY_PERCENTILE} 使用，取值 (0,100]。</li>
 * </ul>
 *
 * @param ruleId           规则唯一标识
 * @param api              目标接口
 * @param metricType       引用的指标口径
 * @param percentile       仅 LATENCY_PERCENTILE 使用，范围 (0,100]
 * @param operator         比较算子
 * @param threshold        阈值（计数/延迟非负，比率 0~1）
 * @param pendingRequired  连续超标多少次进入 FIRING（至少 1）
 * @param recoveryRequired 连续恢复多少次进入 RECOVERED（至少 1）
 */
public record AlertRule(
        String ruleId,
        String api,
        MetricType metricType,
        double percentile,
        ComparisonOperator operator,
        double threshold,
        int pendingRequired,
        int recoveryRequired) {

    /** 非法规则原因码。 */
    public static final String REASON_BLANK_RULE_ID = "BLANK_RULE_ID";
    public static final String REASON_BLANK_API = "BLANK_API";
    public static final String REASON_NULL_METRIC = "NULL_METRIC_TYPE";
    public static final String REASON_NULL_OPERATOR = "NULL_OPERATOR";
    public static final String REASON_NAN_OR_INFINITE_THRESHOLD = "NAN_OR_INFINITE_THRESHOLD";
    public static final String REASON_THRESHOLD_OUT_OF_RANGE = "THRESHOLD_OUT_OF_RANGE";
    public static final String REASON_INVALID_PENDING = "INVALID_PENDING_REQUIRED";
    public static final String REASON_INVALID_RECOVERY = "INVALID_RECOVERY_REQUIRED";
    public static final String REASON_INVALID_PERCENTILE = "INVALID_PERCENTILE";

    /**
     * 创建并校验规则，任何字段非法都抛出 {@link InvalidAlertRuleException}，不会构造出脏规则。
     */
    public static AlertRule of(String ruleId, String api, MetricType metricType, double percentile,
                               ComparisonOperator operator, double threshold,
                               int pendingRequired, int recoveryRequired) {
        if (ruleId == null || ruleId.isBlank()) {
            throw new InvalidAlertRuleException(REASON_BLANK_RULE_ID, "ruleId must not be blank");
        }
        if (api == null || api.isBlank()) {
            throw new InvalidAlertRuleException(REASON_BLANK_API,
                    "api must not be blank for rule " + ruleId);
        }
        if (metricType == null) {
            throw new InvalidAlertRuleException(REASON_NULL_METRIC,
                    "metricType must not be null for rule " + ruleId);
        }
        if (operator == null) {
            throw new InvalidAlertRuleException(REASON_NULL_OPERATOR,
                    "operator must not be null for rule " + ruleId);
        }
        if (Double.isNaN(threshold) || Double.isInfinite(threshold)) {
            throw new InvalidAlertRuleException(REASON_NAN_OR_INFINITE_THRESHOLD,
                    "threshold must be a finite number for rule " + ruleId + ", got " + threshold);
        }
        if (threshold < 0) {
            throw new InvalidAlertRuleException(REASON_THRESHOLD_OUT_OF_RANGE,
                    "threshold must be >= 0 for rule " + ruleId + ", got " + threshold);
        }
        boolean rateMetric = metricType == MetricType.SUCCESS_RATE
                || metricType == MetricType.FAILURE_RATE;
        if (rateMetric && threshold > 1d) {
            throw new InvalidAlertRuleException(REASON_THRESHOLD_OUT_OF_RANGE,
                    "rate threshold must be within [0,1] for rule " + ruleId + ", got " + threshold);
        }
        if (pendingRequired < 1) {
            throw new InvalidAlertRuleException(REASON_INVALID_PENDING,
                    "pendingRequired must be >= 1 for rule " + ruleId
                            + ", got " + pendingRequired);
        }
        if (recoveryRequired < 1) {
            throw new InvalidAlertRuleException(REASON_INVALID_RECOVERY,
                    "recoveryRequired must be >= 1 for rule " + ruleId
                            + ", got " + recoveryRequired);
        }
        if (metricType == MetricType.LATENCY_PERCENTILE
                && (!(percentile > 0d) || percentile > 100d || Double.isNaN(percentile))) {
            throw new InvalidAlertRuleException(REASON_INVALID_PERCENTILE,
                    "percentile must be within (0,100] for rule " + ruleId
                            + ", got " + percentile);
        }
        return new AlertRule(ruleId, api, metricType, percentile, operator, threshold,
                pendingRequired, recoveryRequired);
    }
}
