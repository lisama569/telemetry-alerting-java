package com.github.lisama569.telemetryalertingjava.alerting;

import com.github.lisama569.telemetryalertingjava.exception.InvalidAlertRuleException;
import com.github.lisama569.telemetryalertingjava.model.MetricType;

/**
 * 一条基于窗口指标的告警规则。
 *
 * @param ruleId            规则唯一标识
 * @param endpoint          目标接口
 * @param metric            引用的指标类型
 * @param operator          比较算子
 * @param triggerThreshold  触发阈值；比率类指标取值范围必须为 [0,1]
 * @param pendingStreak     连续满足触发条件的评估次数，达到后进入触发中（必须 &gt;=1）
 * @param recoverStreak     连续不满足触发条件的评估次数，达到后恢复（必须 &gt;=1）
 */
public record AlertRule(String ruleId,
                        String endpoint,
                        MetricType metric,
                        ComparisonOperator operator,
                        double triggerThreshold,
                        int pendingStreak,
                        int recoverStreak) {

    private static boolean isRateMetric(MetricType metric) {
        return metric == MetricType.SUCCESS_RATE || metric == MetricType.FAILURE_RATE;
    }

    /** 校验规则合法性，非法时抛出带原因的异常。 */
    public void validate() {
        if (ruleId == null || ruleId.isBlank()) {
            throw new InvalidAlertRuleException("INVALID_RULE_ID_BLANK", "规则 id 缺失或为空白");
        }
        if (endpoint == null || endpoint.isBlank()) {
            throw new InvalidAlertRuleException("INVALID_RULE_ENDPOINT_BLANK",
                    "规则 " + ruleId + " 的目标接口缺失或为空白");
        }
        if (metric == null) {
            throw new InvalidAlertRuleException("INVALID_RULE_METRIC_UNKNOWN",
                    "规则 " + ruleId + " 引用了未知指标: null");
        }
        if (operator == null) {
            throw new InvalidAlertRuleException("INVALID_RULE_OPERATOR_MISSING",
                    "规则 " + ruleId + " 缺少比较算子");
        }
        if (Double.isNaN(triggerThreshold) || Double.isInfinite(triggerThreshold)) {
            throw new InvalidAlertRuleException("INVALID_RULE_THRESHOLD_ILLEGAL",
                    "规则 " + ruleId + " 的阈值非法: " + triggerThreshold);
        }
        if (isRateMetric(metric)) {
            if (triggerThreshold < 0.0d || triggerThreshold > 1.0d) {
                throw new InvalidAlertRuleException("INVALID_RULE_THRESHOLD_OUT_OF_RANGE",
                        "规则 " + ruleId + " 引用比率指标 " + metric
                                + "，阈值必须在 [0,1] 内: " + triggerThreshold);
            }
        } else if (triggerThreshold < 0.0d) {
            throw new InvalidAlertRuleException("INVALID_RULE_THRESHOLD_NEGATIVE",
                    "规则 " + ruleId + " 的阈值不允许为负: " + triggerThreshold);
        }
        if (pendingStreak < 1) {
            throw new InvalidAlertRuleException("INVALID_RULE_PENDING_STREAK",
                    "规则 " + ruleId + " 的待确认连续次数必须 >= 1: " + pendingStreak);
        }
        if (recoverStreak < 1) {
            throw new InvalidAlertRuleException("INVALID_RULE_RECOVER_STREAK",
                    "规则 " + ruleId + " 的恢复连续次数必须 >= 1: " + recoverStreak);
        }
    }
}
