package com.github.lisama569.telemetryalertingjava.alerting;

import com.github.lisama569.telemetryalertingjava.common.TimeSource;
import com.github.lisama569.telemetryalertingjava.exception.EvaluationException;
import com.github.lisama569.telemetryalertingjava.exception.InvalidAlertRuleException;
import com.github.lisama569.telemetryalertingjava.exception.TelemetryException;
import com.github.lisama569.telemetryalertingjava.model.MetricSnapshot;
import com.github.lisama569.telemetryalertingjava.model.MetricType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认告警评估器。
 *
 * 状态迁移（每次 evaluate 视为一次评估节拍）：
 * <pre>
 *                 breach 且 breachStreak 达到 pendingStreak
 *  NOT_TRIGGERED/PENDING ───────────────────────────────► FIRING
 *  NOT_TRIGGERED ──breach──► PENDING（待确认，短暂抖动不会直接触发）
 *  PENDING/FIRING ──healthy 一次──► streak 清零（抖动不会解除/阻止确认）
 *  FIRING ──连续 healthy 达到 recoverStreak──► RESOLVED（恢复需稳定）
 *  RESOLVED ──breach──► PENDING（已恢复后再次抖动，需重新确认）
 * </pre>
 * 幂等：相同 (规则, 评估节拍, 指标值, 窗口) 重复评估直接回放上次结果，
 * 不改变状态与 streak。
 */
public class DefaultAlertEvaluator implements AlertEvaluator {

    private static final class RuleHolder {
        final AlertRule rule;
        final Object lock = new Object();
        final AlertStateEntity state = new AlertStateEntity();

        RuleHolder(AlertRule rule) {
            this.rule = rule;
        }
    }

    private final Map<String, RuleHolder> holders = new ConcurrentHashMap<>();
    private final TimeSource timeSource;

    public DefaultAlertEvaluator(TimeSource timeSource) {
        if (timeSource == null) {
            throw new TelemetryException("INVALID_TIME_SOURCE", "时间源不允许为 null");
        }
        this.timeSource = timeSource;
    }

    @Override
    public void registerRule(AlertRule rule) {
        if (rule == null) {
            throw new InvalidAlertRuleException("INVALID_RULE_NULL", "规则不允许为 null");
        }
        rule.validate();
        RuleHolder holder = new RuleHolder(rule);
        if (holders.putIfAbsent(rule.ruleId(), holder) != null) {
            throw new InvalidAlertRuleException("INVALID_RULE_DUPLICATE_ID",
                    "规则 id 已存在: " + rule.ruleId());
        }
    }

    @Override
    public EvaluationResult evaluate(String ruleId, MetricSnapshot snapshot) {
        if (ruleId == null || ruleId.isBlank()) {
            throw new EvaluationException("EVAL_RULE_ID_BLANK", "评估时规则 id 缺失或为空白");
        }
        RuleHolder holder = holders.get(ruleId);
        if (holder == null) {
            throw new EvaluationException("EVAL_RULE_UNKNOWN", "规则未注册: " + ruleId);
        }
        if (snapshot == null) {
            throw new EvaluationException("EVAL_METRIC_MISSING",
                    "规则 " + ruleId + " 评估时指标快照缺失");
        }
        AlertRule rule = holder.rule;
        if (!rule.endpoint().equals(snapshot.endpoint())) {
            throw new EvaluationException("EVAL_METRIC_ENDPOINT_MISMATCH",
                    "规则 " + ruleId + " 绑定接口 " + rule.endpoint()
                            + "，但快照来自 " + snapshot.endpoint());
        }
        double metricValue = extractMetric(snapshot, rule.metric());
        long now = timeSource.nowMillis();

        // 同一评估节拍（时间桶）+ 相同指标输入 => 幂等回放
        String signature = buildSignature(now, snapshot, rule.metric(), metricValue);

        synchronized (holder.lock) {
            AlertStateEntity st = holder.state;
            if (signature.equals(st.getLastEvaluationSignature())) {
                return new EvaluationResult(ruleId, st.getState(),
                        st.getBreachStreak() > 0 || st.getState() == AlertState.FIRING,
                        st.getBreachStreak(), st.getHealthyStreak(), metricValue,
                        false, true,
                        "重复评估（相同时间节拍与指标输入），幂等回放，状态保持 " + st.getState(),
                        now);
            }

            boolean breach = rule.operator().matches(metricValue, rule.triggerThreshold());
            AlertState before = st.getState();
            int breachStreak = st.getBreachStreak();
            int healthyStreak = st.getHealthyStreak();
            String reason;

            if (breach) {
                breachStreak++;
                healthyStreak = 0;
                switch (before) {
                    case NOT_TRIGGERED -> {
                        if (breachStreak >= rule.pendingStreak()) {
                            // pendingStreak==1 时直接进入触发中
                            st.transitionTo(AlertState.FIRING, now);
                            reason = "命中第 " + breachStreak + " 次达到待确认次数 "
                                    + rule.pendingStreak() + "，未触发→触发中";
                        } else {
                            st.transitionTo(AlertState.PENDING, now);
                            reason = "指标命中（第 " + breachStreak + "/" + rule.pendingStreak()
                                    + " 次），未触发→待确认，单次抖动不触发";
                        }
                    }
                    case PENDING -> {
                        if (breachStreak >= rule.pendingStreak()) {
                            st.transitionTo(AlertState.FIRING, now);
                            reason = "连续命中 " + breachStreak + " 次达到待确认次数 "
                                    + rule.pendingStreak() + "，待确认→触发中";
                        } else {
                            reason = "指标继续命中（第 " + breachStreak + "/"
                                    + rule.pendingStreak() + " 次），保持待确认";
                        }
                    }
                    case FIRING -> reason = "指标持续命中，保持触发中";
                    case RESOLVED -> {
                        st.transitionTo(AlertState.PENDING, now);
                        reason = "已恢复后指标再次命中，已恢复→待确认，需重新确认";
                    }
                    default -> throw new IllegalStateException("未知告警状态: " + before);
                }
            } else {
                healthyStreak++;
                breachStreak = 0;
                switch (before) {
                    case NOT_TRIGGERED -> reason = "指标健康，保持未触发";
                    case PENDING -> {
                        st.transitionTo(AlertState.NOT_TRIGGERED, now);
                        reason = "待确认期间指标恢复健康，抖动未达到确认次数，待确认→未触发";
                    }
                    case FIRING -> {
                        if (healthyStreak >= rule.recoverStreak()) {
                            st.transitionTo(AlertState.RESOLVED, now);
                            reason = "连续健康 " + healthyStreak + " 次达到恢复稳定次数 "
                                    + rule.recoverStreak() + "，触发中→已恢复";
                        } else {
                            reason = "指标恢复健康（第 " + healthyStreak + "/"
                                    + rule.recoverStreak() + " 次），抖动不解除告警，保持触发中";
                        }
                    }
                    case RESOLVED -> reason = "指标持续健康，保持已恢复";
                    default -> throw new IllegalStateException("未知告警状态: " + before);
                }
            }

            st.setStreaks(breachStreak, healthyStreak);
            st.setLastEvaluationSignature(signature);
            AlertState after = st.getState();
            return new EvaluationResult(ruleId, after, breach,
                    breachStreak, healthyStreak, metricValue,
                    after != before, false,
                    "指标 " + rule.metric() + "=" + metricValue
                            + "，阈值 " + rule.operator() + " " + rule.triggerThreshold()
                            + "，命中=" + breach + "；" + reason,
                    now);
        }
    }

    @Override
    public AlertStateEntity getState(String ruleId) {
        RuleHolder holder = holders.get(ruleId);
        if (holder == null) {
            throw new EvaluationException("EVAL_RULE_UNKNOWN", "规则未注册: " + ruleId);
        }
        synchronized (holder.lock) {
            // 返回拷贝，避免外部修改内部状态
            AlertStateEntity copy = new AlertStateEntity();
            copy.transitionTo(holder.state.getState(), holder.state.getStateChangedAtMillis());
            copy.setStreaks(holder.state.getBreachStreak(), holder.state.getHealthyStreak());
            copy.setLastEvaluationSignature(holder.state.getLastEvaluationSignature());
            return copy;
        }
    }

    @Override
    public AlertRule getRule(String ruleId) {
        RuleHolder holder = holders.get(ruleId);
        if (holder == null) {
            throw new EvaluationException("EVAL_RULE_UNKNOWN", "规则未注册: " + ruleId);
        }
        return holder.rule;
    }

    private static double extractMetric(MetricSnapshot s, MetricType metric) {
        return switch (metric) {
            case TOTAL_COUNT -> s.totalCount();
            case SUCCESS_COUNT -> s.successCount();
            case FAILURE_COUNT -> s.failureCount();
            case SUCCESS_RATE -> s.successRate();
            case FAILURE_RATE -> s.failureRate();
            case AVG_LATENCY_MILLIS -> s.avgLatencyMillis();
            case P50_LATENCY_MILLIS -> s.p50LatencyMillis();
            case P95_LATENCY_MILLIS -> s.p95LatencyMillis();
            case P99_LATENCY_MILLIS -> s.p99LatencyMillis();
            case MAX_LATENCY_MILLIS -> s.maxLatencyMillis();
        };
    }

    private static String buildSignature(long now, MetricSnapshot snapshot,
                                         MetricType metric, double metricValue) {
        // 同一毫秒时间桶视为同一个评估节拍
        return (now / 10L)
                + "|" + snapshot.windowStartMillis()
                + "|" + snapshot.windowEndMillis()
                + "|" + snapshot.windowMillis()
                + "|" + snapshot.totalCount()
                + "|" + metric
                + "|" + metricValue;
    }
}
