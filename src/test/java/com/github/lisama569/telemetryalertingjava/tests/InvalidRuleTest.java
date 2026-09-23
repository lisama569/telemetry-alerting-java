package com.github.lisama569.telemetryalertingjava.tests;

import com.github.lisama569.telemetryalertingjava.alerting.AlertRule;
import com.github.lisama569.telemetryalertingjava.alerting.ComparisonOperator;
import com.github.lisama569.telemetryalertingjava.alerting.DefaultAlertEvaluator;
import com.github.lisama569.telemetryalertingjava.exception.EvaluationException;
import com.github.lisama569.telemetryalertingjava.exception.InvalidAlertRuleException;
import com.github.lisama569.telemetryalertingjava.model.MetricSnapshot;
import com.github.lisama569.telemetryalertingjava.model.MetricType;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 非法规则不得默认放行，必须拒绝并给出可区分原因。 */
class InvalidRuleTest {

    private static final Logger log = LoggerFactory.getLogger(InvalidRuleTest.class);
    private static final String EP = "/api/x";

    private final MutableTimeSource clock = new MutableTimeSource(1_000L);
    private final DefaultAlertEvaluator evaluator = new DefaultAlertEvaluator(clock);

    private void assertRuleRejected(AlertRule rule, String expectedCode) {
        InvalidAlertRuleException ex = assertThrows(InvalidAlertRuleException.class,
                () -> evaluator.registerRule(rule), "非法规则必须被拒绝: " + expectedCode);
        log.info("[非法规则] 输入: ruleId={}, endpoint={}, metric={}, operator={}, threshold={}, pending={}, recover={} => 拒绝原因码={}",
                rule == null ? null : rule.ruleId(),
                rule == null ? null : rule.endpoint(),
                rule == null ? null : rule.metric(),
                rule == null ? null : rule.operator(),
                rule == null ? null : rule.triggerThreshold(),
                rule == null ? null : rule.pendingStreak(),
                rule == null ? null : rule.recoverStreak(),
                ex.getReasonCode());
        assertEquals(expectedCode, ex.getReasonCode());
    }

    @Test
    void invalidRulesRejectedWithDistinctReasons() {
        assertRuleRejected(null, "INVALID_RULE_NULL");
        assertRuleRejected(new AlertRule("  ", EP, MetricType.FAILURE_RATE,
                ComparisonOperator.GREATER_THAN, 0.5d, 1, 1), "INVALID_RULE_ID_BLANK");
        assertRuleRejected(new AlertRule("r1", " ", MetricType.FAILURE_RATE,
                ComparisonOperator.GREATER_THAN, 0.5d, 1, 1), "INVALID_RULE_ENDPOINT_BLANK");
        assertRuleRejected(new AlertRule("r1", EP, null,
                ComparisonOperator.GREATER_THAN, 0.5d, 1, 1), "INVALID_RULE_METRIC_UNKNOWN");
        assertRuleRejected(new AlertRule("r1", EP, MetricType.FAILURE_RATE,
                null, 0.5d, 1, 1), "INVALID_RULE_OPERATOR_MISSING");
        assertRuleRejected(new AlertRule("r1", EP, MetricType.FAILURE_RATE,
                ComparisonOperator.GREATER_THAN, Double.NaN, 1, 1), "INVALID_RULE_THRESHOLD_ILLEGAL");
        assertRuleRejected(new AlertRule("r1", EP, MetricType.FAILURE_RATE,
                ComparisonOperator.GREATER_THAN, 1.5d, 1, 1), "INVALID_RULE_THRESHOLD_OUT_OF_RANGE");
        assertRuleRejected(new AlertRule("r1", EP, MetricType.P95_LATENCY_MILLIS,
                ComparisonOperator.GREATER_THAN, -1.0d, 1, 1), "INVALID_RULE_THRESHOLD_NEGATIVE");
        assertRuleRejected(new AlertRule("r1", EP, MetricType.FAILURE_RATE,
                ComparisonOperator.GREATER_THAN, 0.5d, 0, 1), "INVALID_RULE_PENDING_STREAK");
        assertRuleRejected(new AlertRule("r1", EP, MetricType.FAILURE_RATE,
                ComparisonOperator.GREATER_THAN, 0.5d, 1, -2), "INVALID_RULE_RECOVER_STREAK");

        // 合法规则注册成功后，重复 id 必须拒绝
        AlertRule good = new AlertRule("dup", EP, MetricType.FAILURE_RATE,
                ComparisonOperator.GREATER_THAN, 0.5d, 2, 2);
        evaluator.registerRule(good);
        assertRuleRejected(good, "INVALID_RULE_DUPLICATE_ID");
        log.info("[非法规则] 判定依据: 10 类非法构造 + 重复 id 全部返回各自原因码，无默认放行");
    }

    @Test
    void evaluationOfUnknownRuleAndMissingMetricRejected() {
        AlertRule rule = new AlertRule("r", EP, MetricType.FAILURE_RATE,
                ComparisonOperator.GREATER_THAN, 0.5d, 1, 1);
        evaluator.registerRule(rule);

        MetricSnapshot wrongEndpoint = new MetricSnapshot("/api/other", 10_000L,
                0L, 1L, 1L, 1L, 0L, 0.0d, 0.0d, 0.0d, 0L, 0L, 0L, 0L, 0L);

        EvaluationException e1 = assertThrows(EvaluationException.class,
                () -> evaluator.evaluate("not-registered", wrongEndpoint));
        EvaluationException e2 = assertThrows(EvaluationException.class,
                () -> evaluator.evaluate("r", null));
        EvaluationException e3 = assertThrows(EvaluationException.class,
                () -> evaluator.evaluate("r", wrongEndpoint));
        log.info("[非法规则] 评估异常原因码: unknownRule={}, missingMetric={}, endpointMismatch={}",
                e1.getReasonCode(), e2.getReasonCode(), e3.getReasonCode());
        assertEquals("EVAL_RULE_UNKNOWN", e1.getReasonCode());
        assertEquals("EVAL_METRIC_MISSING", e2.getReasonCode());
        assertEquals("EVAL_METRIC_ENDPOINT_MISMATCH", e3.getReasonCode());
    }
}
