package com.github.lisama569.telemetryalertingjava.tests;

import com.github.lisama569.telemetryalertingjava.alert.AlertEvaluation;
import com.github.lisama569.telemetryalertingjava.alert.AlertEvaluator;
import com.github.lisama569.telemetryalertingjava.alert.AlertRule;
import com.github.lisama569.telemetryalertingjava.alert.AlertState;
import com.github.lisama569.telemetryalertingjava.alert.ComparisonOperator;
import com.github.lisama569.telemetryalertingjava.alert.InvalidAlertRuleException;
import com.github.lisama569.telemetryalertingjava.core.FixedClock;
import com.github.lisama569.telemetryalertingjava.metrics.CallStatus;
import com.github.lisama569.telemetryalertingjava.metrics.MetricType;
import com.github.lisama569.telemetryalertingjava.metrics.MetricsRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 告警状态机：抖动抑制、恢复稳定性、幂等评估与非法规则拒绝。
 */
class AlertEvaluatorTest {

    private static final Logger log = LoggerFactory.getLogger(AlertEvaluatorTest.class);

    /** 失败次数 >= 2 为超标，需连续 2 次超标 FIRING，连续 2 次达标 RECOVERED。 */
    private AlertRule rule() {
        return AlertRule.of("high-failure", "/order", MetricType.FAILURE_COUNT, 0,
                ComparisonOperator.GTE, 2d, 2, 2);
    }

    private AlertEvaluation eval(AlertEvaluator evaluator, String id, long t) {
        AlertEvaluation e = evaluator.evaluateAt(id, t);
        log.info("评估输入: rule={} t={} breached={} metricValue={} 连续超标={} 连续恢复={}",
                e.ruleId(), t, e.breached(), e.metricValue(),
                e.consecutiveBreaches(), e.consecutiveRecoveries());
        log.info("状态判定: {}", e.reason());
        return e;
    }

    /**
     * 用 10s 窗口演示完整迁移：超标通过写入失败事件制造，达标通过时钟滚动把失败桶淘汰来制造，
     * 因此状态判定完全对应真实窗口聚合值，可复现。
     */
    @Test
    void fullStateTransitionWithJitterSuppressionAndStableRecovery() {
        // 100s 窗口；周期之间相隔 >100s，保证上一周期数据彻底滚出，聚合值可预期。
        // 超标条件 FAILURE_COUNT >= 2：进入 PENDING 需要一次超标评估（窗口内>=2 个失败），
        // 进入 FIRING 需要连续 2 次超标评估；进入 RECOVERED 需要连续 2 次达标评估。
        FixedClock clock = new FixedClock(0L);
        MetricsRegistry registry = new MetricsRegistry(100_000L, 1_000L, clock);
        AlertEvaluator evaluator = new AlertEvaluator(registry, clock);
        evaluator.register(rule());
        String id = "high-failure";

        // 初始：1 失败（failureCount=1 < 2），未超标
        registry.record("/order", CallStatus.FAILURE, 10L, 0L);
        assertEquals(AlertState.NOT_TRIGGERED, eval(evaluator, id, 100L).state());

        // 第 1 次超标（两个失败在窗口内）-> PENDING（单次抖动不触发 FIRING）
        clock.setMillis(1_000L);
        registry.record("/order", CallStatus.FAILURE, 10L, 1_000L);
        assertEquals(2L, registry.snapshot("/order").orElseThrow().failureCount());
        assertEquals(AlertState.PENDING, eval(evaluator, id, 1_100L).state());

        // 短暂恢复：到 200s 失败桶全部滚出，仅留成功样本 -> 一次达标，PENDING 被取消
        clock.setMillis(200_000L);
        registry.record("/order", CallStatus.SUCCESS, 5L, 200_000L);
        assertEquals(0L, registry.snapshot("/order").orElseThrow().failureCount());
        assertEquals(AlertState.NOT_TRIGGERED, eval(evaluator, id, 200_100L).state());

        // 再次连续超标：写入两个失败后做第 1 次超标评估 -> PENDING
        clock.setMillis(201_000L);
        registry.record("/order", CallStatus.FAILURE, 10L, 201_000L);
        clock.setMillis(202_000L);
        registry.record("/order", CallStatus.FAILURE, 10L, 202_000L);
        assertEquals(AlertState.PENDING, eval(evaluator, id, 202_100L).state());
        // 再写一个失败，第 2 次连续超标评估 -> FIRING
        clock.setMillis(203_000L);
        registry.record("/order", CallStatus.FAILURE, 10L, 203_000L);
        assertEquals(AlertState.FIRING, eval(evaluator, id, 203_100L).state());

        // 恢复抖动：到 304s 本周期失败全部滚出，failureCount=0 达标；
        // 连续恢复仅 1 次，告警不得解除
        clock.setMillis(304_000L);
        registry.record("/order", CallStatus.SUCCESS, 5L, 304_000L);
        assertEquals(AlertState.FIRING, eval(evaluator, id, 304_100L).state());

        // 指标再次抖动超标：连续两次评估都满足 >=2 个失败，恢复计数归零，仍 FIRING
        registry.record("/order", CallStatus.FAILURE, 10L, 304_000L);
        clock.setMillis(305_000L);
        registry.record("/order", CallStatus.FAILURE, 10L, 305_000L);
        assertEquals(AlertState.FIRING, eval(evaluator, id, 305_100L).state());

        // 稳定恢复：到 406s 失败桶全部滚出；连续第 1 个达标时刻仍 FIRING
        clock.setMillis(406_000L);
        registry.record("/order", CallStatus.SUCCESS, 5L, 406_000L);
        assertEquals(AlertState.FIRING, eval(evaluator, id, 406_100L).state());
        // 连续第 2 个达标时刻（无新失败）-> RECOVERED
        clock.setMillis(407_000L);
        assertEquals(AlertState.RECOVERED, eval(evaluator, id, 407_100L).state());

        // 恢复后再次超标（窗口内两个失败）-> 重新 PENDING
        registry.record("/order", CallStatus.FAILURE, 10L, 407_000L);
        clock.setMillis(408_000L);
        registry.record("/order", CallStatus.FAILURE, 10L, 408_000L);
        assertEquals(AlertState.PENDING, eval(evaluator, id, 408_100L).state());
    }

    @Test
    void repeatedEvaluationAtSameTimeIsIdempotent() {
        FixedClock clock = new FixedClock(10L);
        MetricsRegistry registry = new MetricsRegistry(60_000L, 1_000L, clock);
        AlertEvaluator evaluator = new AlertEvaluator(registry, clock);
        evaluator.register(rule());

        registry.record("/order", CallStatus.FAILURE, 10L, 0L);
        registry.record("/order", CallStatus.FAILURE, 10L, 10L);
        AlertEvaluation first = eval(evaluator, "high-failure", 100L);
        AlertEvaluation second = eval(evaluator, "high-failure", 100L);
        AlertEvaluation third = evaluator.evaluateAt("high-failure", 100L);

        assertEquals(first.state(), second.state());
        assertSame(second, third, "同一时刻重复评估必须返回同一结果对象");
        assertEquals(1, first.consecutiveBreaches(), "重复评估不得累计超标次数");
        log.info("同刻三次评估，状态={} 连续超标计数={}（未随重复评估增长，幂等成立）",
                third.state(), third.consecutiveBreaches());
    }

    @Test
    void rejectsUnknownMetricAndUnknownRuleInsteadOfSilentlyPassing() {
        FixedClock clock = new FixedClock(0L);
        MetricsRegistry registry = new MetricsRegistry(60_000L, 1_000L, clock);
        AlertEvaluator evaluator = new AlertEvaluator(registry, clock);
        evaluator.register(rule());

        InvalidAlertRuleException unknownMetric = assertThrows(InvalidAlertRuleException.class,
                () -> evaluator.evaluateAt("high-failure", 1L));
        assertEquals(AlertEvaluator.REASON_UNKNOWN_METRIC, unknownMetric.reason());
        log.info("[拒绝评估] 窗口内无指标数据 原因码={} 说明={}",
                unknownMetric.reason(), unknownMetric.getMessage());

        InvalidAlertRuleException unknownRule = assertThrows(InvalidAlertRuleException.class,
                () -> evaluator.evaluateAt("not-exist", 1L));
        assertEquals(AlertEvaluator.REASON_UNKNOWN_RULE, unknownRule.reason());
        log.info("[拒绝评估] 规则未注册 原因码={} 说明={}",
                unknownRule.reason(), unknownRule.getMessage());
    }

    @Test
    void rejectsIllegalRulesWithDistinguishableReasons() {
        assertRuleRejected(AlertRule.REASON_BLANK_RULE_ID,
                () -> AlertRule.of(" ", "/a", MetricType.CALL_COUNT, 0,
                        ComparisonOperator.GT, 1d, 1, 1));
        assertRuleRejected(AlertRule.REASON_BLANK_API,
                () -> AlertRule.of("r", null, MetricType.CALL_COUNT, 0,
                        ComparisonOperator.GT, 1d, 1, 1));
        assertRuleRejected(AlertRule.REASON_NULL_METRIC,
                () -> AlertRule.of("r", "/a", null, 0,
                        ComparisonOperator.GT, 1d, 1, 1));
        assertRuleRejected(AlertRule.REASON_NULL_OPERATOR,
                () -> AlertRule.of("r", "/a", MetricType.CALL_COUNT, 0,
                        null, 1d, 1, 1));
        assertRuleRejected(AlertRule.REASON_NAN_OR_INFINITE_THRESHOLD,
                () -> AlertRule.of("r", "/a", MetricType.CALL_COUNT, 0,
                        ComparisonOperator.GT, Double.NaN, 1, 1));
        assertRuleRejected(AlertRule.REASON_THRESHOLD_OUT_OF_RANGE,
                () -> AlertRule.of("r", "/a", MetricType.FAILURE_RATE, 0,
                        ComparisonOperator.GT, 1.5d, 1, 1));
        assertRuleRejected(AlertRule.REASON_INVALID_PENDING,
                () -> AlertRule.of("r", "/a", MetricType.CALL_COUNT, 0,
                        ComparisonOperator.GT, 1d, 0, 1));
        assertRuleRejected(AlertRule.REASON_INVALID_RECOVERY,
                () -> AlertRule.of("r", "/a", MetricType.CALL_COUNT, 0,
                        ComparisonOperator.GT, 1d, 1, -1));
        assertRuleRejected(AlertRule.REASON_INVALID_PERCENTILE,
                () -> AlertRule.of("r", "/a", MetricType.LATENCY_PERCENTILE, 0,
                        ComparisonOperator.GT, 100d, 1, 1));
        assertRuleRejected(AlertRule.REASON_INVALID_PERCENTILE,
                () -> AlertRule.of("r", "/a", MetricType.LATENCY_PERCENTILE, 101,
                        ComparisonOperator.GT, 100d, 1, 1));
    }

    @Test
    void rejectsDuplicateRuleIdAndBackwardEvaluationTime() {
        FixedClock clock = new FixedClock(0L);
        MetricsRegistry registry = new MetricsRegistry(60_000L, 1_000L, clock);
        AlertEvaluator evaluator = new AlertEvaluator(registry, clock);
        evaluator.register(rule());
        registry.record("/order", CallStatus.SUCCESS, 10L, 0L);
        evaluator.evaluateAt("high-failure", 100L);

        InvalidAlertRuleException dup = assertThrows(InvalidAlertRuleException.class,
                () -> evaluator.register(rule()));
        log.info("[拒绝注册] 重复 ruleId 原因码={} 说明={}", dup.reason(), dup.getMessage());

        InvalidAlertRuleException back = assertThrows(InvalidAlertRuleException.class,
                () -> evaluator.evaluateAt("high-failure", 99L));
        assertEquals(AlertEvaluator.REASON_NON_MONOTONIC_TIME, back.reason());
        log.info("[拒绝评估] 时间回退 原因码={} 说明={}", back.reason(), back.getMessage());
    }

    private void assertRuleRejected(String expectedReason, Runnable action) {
        InvalidAlertRuleException ex = assertThrows(InvalidAlertRuleException.class, action::run);
        assertEquals(expectedReason, ex.reason());
        log.info("[非法规则拒绝] 原因码={} 说明={}", ex.reason(), ex.getMessage());
    }
}
