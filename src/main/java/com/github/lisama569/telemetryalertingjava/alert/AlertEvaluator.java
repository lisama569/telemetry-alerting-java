package com.github.lisama569.telemetryalertingjava.alert;

import com.github.lisama569.telemetryalertingjava.core.Clock;
import com.github.lisama569.telemetryalertingjava.core.SystemClock;
import com.github.lisama569.telemetryalertingjava.metrics.MetricsRegistry;
import com.github.lisama569.telemetryalertingjava.metrics.WindowSnapshot;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 告警评估器：维护每条规则的状态机，线程安全；同一评估时刻重复评估幂等。
 *
 * <p>状态迁移规则（每一次评估只看当前窗口指标是否超标 breach）：
 * <pre>
 *   NOT_TRIGGERED --breach, 连续>=pending--> FIRING（pendingRequired=1 时首次超标即触发）
 *   NOT_TRIGGERED --breach, 连续<pending---> PENDING（计数+1，抖动不立即触发）
 *   NOT_TRIGGERED --达标--------------------> NOT_TRIGGERED
 *   PENDING       --breach, 连续>=pending--> FIRING
 *   PENDING       --breach, 连续<pending---> PENDING（计数+1）
 *   PENDING       --达标--------------------> NOT_TRIGGERED（抖动未确认即归零）
 *   FIRING        --达标, 连续>=recovery---> RECOVERED
 *   FIRING        --达标, 连续<recovery----> FIRING（恢复计数+1，抖动不解警）
 *   FIRING        --breach------------------> FIRING（恢复计数归零）
 *   RECOVERED     --breach, 连续>=pending--> FIRING
 *   RECOVERED     --breach, 连续<pending---> PENDING(连续超标=1)
 *   RECOVERED     --达标--------------------> RECOVERED
 * </pre>
 */
public class AlertEvaluator {

    /** 评估期异常原因码。 */
    public static final String REASON_UNKNOWN_RULE = "UNKNOWN_RULE";
    public static final String REASON_UNKNOWN_METRIC = "UNKNOWN_METRIC";
    public static final String REASON_NON_MONOTONIC_TIME = "NON_MONOTONIC_EVALUATION_TIME";

    private final MetricsRegistry registry;
    private final Clock clock;
    private final ConcurrentHashMap<String, RuleState> states = new ConcurrentHashMap<>();

    public AlertEvaluator(MetricsRegistry registry) {
        this(registry, new SystemClock());
    }

    public AlertEvaluator(MetricsRegistry registry, Clock clock) {
        if (registry == null) {
            throw new IllegalArgumentException("registry must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.registry = registry;
        this.clock = clock;
    }

    /** 注册并校验规则，非法规则抛出 {@link InvalidAlertRuleException}。重复注册同 ID 也拒绝。 */
    public void register(AlertRule rule) {
        if (rule == null) {
            throw new InvalidAlertRuleException("NULL_RULE", "rule must not be null");
        }
        RuleState state = new RuleState(rule);
        if (states.putIfAbsent(rule.ruleId(), state) != null) {
            throw new InvalidAlertRuleException("DUPLICATE_RULE_ID",
                    "rule already registered: " + rule.ruleId());
        }
    }

    /** 使用评估器时钟评估一次。 */
    public AlertEvaluation evaluate(String ruleId) {
        return evaluateAt(ruleId, clock.nowMillis());
    }

    /**
     * 在指定时刻评估（测试可复现）。
     * 同一规则、同一 evaluationMillis 重复调用直接返回上次结果，状态与计数不发生任何变化（幂等）。
     */
    public AlertEvaluation evaluateAt(String ruleId, long evaluationMillis) {
        RuleState state = states.get(ruleId);
        if (state == null) {
            throw new InvalidAlertRuleException(REASON_UNKNOWN_RULE,
                    "rule is not registered: " + ruleId);
        }
        synchronized (state) {
            if (state.lastEvaluationMillis == evaluationMillis && state.lastResult != null) {
                return state.lastResult;
            }
            if (state.lastResult != null && evaluationMillis < state.lastEvaluationMillis) {
                throw new InvalidAlertRuleException(REASON_NON_MONOTONIC_TIME,
                        "evaluation time moved backwards for rule " + ruleId + ": last="
                                + state.lastEvaluationMillis + ", current=" + evaluationMillis);
            }
            AlertRule rule = state.rule;
            Optional<WindowSnapshot> snapshot = registry.snapshot(rule.api());
            if (snapshot.isEmpty()) {
                // 引用的接口窗口内没有任何数据：不默认当作达标放行，必须显式拒绝
                throw new InvalidAlertRuleException(REASON_UNKNOWN_METRIC,
                        "no metric data in window for api=" + rule.api()
                                + " referenced by rule " + ruleId);
            }
            double value = snapshot.get().value(rule.metricType(), rule.percentile());
            boolean breached = rule.operator().test(value, rule.threshold());
            AlertEvaluation result = advance(state, value, breached, evaluationMillis);
            state.lastEvaluationMillis = evaluationMillis;
            state.lastResult = result;
            return result;
        }
    }

    public AlertState stateOf(String ruleId) {
        RuleState state = states.get(ruleId);
        if (state == null) {
            throw new InvalidAlertRuleException(REASON_UNKNOWN_RULE,
                    "rule is not registered: " + ruleId);
        }
        synchronized (state) {
            return state.state;
        }
    }

    /** 纯状态迁移逻辑：依据当前状态、超标与否更新计数并产出评估结果，调用方持锁。 */
    private AlertEvaluation advance(RuleState state, double value, boolean breached, long at) {
        AlertRule rule = state.rule;
        AlertState previous = state.state;
        AlertState next = previous;
        String basis = "";

        if (breached) {
            state.consecutiveRecoveries = 0;
            state.consecutiveBreaches++;
            switch (previous) {
                case NOT_TRIGGERED, RECOVERED -> {
                    if (state.consecutiveBreaches >= rule.pendingRequired()) {
                        next = AlertState.FIRING;
                        basis = "breach detected (" + renderCompare(rule, value)
                                + "), pendingRequired=" + rule.pendingRequired()
                                + " met immediately, FIRING";
                    } else {
                        next = AlertState.PENDING;
                        basis = "breach detected (" + renderCompare(rule, value)
                                + "), enter PENDING awaiting " + rule.pendingRequired()
                                + " consecutive breach(es)";
                    }
                }
                case PENDING -> {
                    if (state.consecutiveBreaches >= rule.pendingRequired()) {
                        next = AlertState.FIRING;
                        basis = "breach sustained for " + state.consecutiveBreaches
                                + "/" + rule.pendingRequired() + " evaluation(s), FIRING";
                    } else {
                        basis = "breach sustained for " + state.consecutiveBreaches
                                + "/" + rule.pendingRequired() + " evaluation(s), stay PENDING";
                    }
                }
                case FIRING -> basis = "still breaching (" + renderCompare(rule, value)
                        + "), remain FIRING; recovery streak reset";
            }
        } else {
            state.consecutiveBreaches = 0;
            state.consecutiveRecoveries++;
            switch (previous) {
                case NOT_TRIGGERED -> basis = "within threshold ("
                        + renderCompare(rule, value) + "), remain NOT_TRIGGERED";
                case RECOVERED -> basis = "still within threshold ("
                        + renderCompare(rule, value) + "), remain RECOVERED";
                case PENDING -> {
                    next = AlertState.NOT_TRIGGERED;
                    basis = "recovered before confirmation (" + renderCompare(rule, value)
                            + "), PENDING cancelled, back to NOT_TRIGGERED";
                }
                case FIRING -> {
                    if (state.consecutiveRecoveries >= rule.recoveryRequired()) {
                        next = AlertState.RECOVERED;
                        basis = "within threshold for " + state.consecutiveRecoveries
                                + "/" + rule.recoveryRequired()
                                + " consecutive evaluation(s), RECOVERED";
                    } else {
                        basis = "within threshold for " + state.consecutiveRecoveries
                                + "/" + rule.recoveryRequired()
                                + " evaluation(s), jitter does not clear alert, remain FIRING";
                    }
                }
            }
        }
        state.state = next;
        return new AlertEvaluation(rule.ruleId(), at, next, breached, value,
                state.consecutiveBreaches, state.consecutiveRecoveries,
                previous + " -> " + next + ": " + basis);
    }

    private static String renderCompare(AlertRule rule, double value) {
        return rule.metricType()
                + (rule.metricType() == com.github.lisama569.telemetryalertingjava.metrics.MetricType.LATENCY_PERCENTILE
                        ? "(p" + rule.percentile() + ")" : "")
                + "=" + value + " " + rule.operator().render(value, rule.threshold());
    }

    /** 单条规则的可变状态，仅在 synchronized(this) 下访问。 */
    private static final class RuleState {
        private final AlertRule rule;
        private AlertState state = AlertState.NOT_TRIGGERED;
        private int consecutiveBreaches;
        private int consecutiveRecoveries;
        private long lastEvaluationMillis = Long.MIN_VALUE;
        private AlertEvaluation lastResult;

        private RuleState(AlertRule rule) {
            this.rule = rule;
        }
    }
}
