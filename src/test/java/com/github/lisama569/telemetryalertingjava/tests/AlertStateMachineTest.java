package com.github.lisama569.telemetryalertingjava.tests;

import com.github.lisama569.telemetryalertingjava.alerting.AlertRule;
import com.github.lisama569.telemetryalertingjava.alerting.AlertState;
import com.github.lisama569.telemetryalertingjava.alerting.ComparisonOperator;
import com.github.lisama569.telemetryalertingjava.alerting.DefaultAlertEvaluator;
import com.github.lisama569.telemetryalertingjava.alerting.EvaluationResult;
import com.github.lisama569.telemetryalertingjava.collector.InMemoryMetricsCollector;
import com.github.lisama569.telemetryalertingjava.model.CallRecordPoint;
import com.github.lisama569.telemetryalertingjava.model.CallStatus;
import com.github.lisama569.telemetryalertingjava.model.MetricType;
import com.github.lisama569.telemetryalertingjava.model.MetricSnapshot;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 告警状态机：
 * 短暂抖动不得触发/解除告警；连续确认后触发；恢复需连续稳定；重复评估幂等。
 * 每个评估节拍把时钟推进超过一个窗口并重建采集器，等价于窗口内只有当节拍数据，
 * 从而让 breach/healthy 序列完全确定、可复现。
 */
class AlertStateMachineTest {

    private static final Logger log = LoggerFactory.getLogger(AlertStateMachineTest.class);
    private static final String EP = "/api/transfer";
    private static final long WINDOW = 10_000L;
    private static final long TICK = WINDOW + 1L;

    private final long t0 = 7_000_000L;
    private final MutableTimeSource clock = new MutableTimeSource(t0);
    private DefaultAlertEvaluator evaluator;
    private InMemoryMetricsCollector collector;

    /** 进入一个新的评估节拍：时钟越过整个窗口，采集器重建（旧数据已全部过期）。 */
    private void newTick(int tickIndex) {
        clock.setTime(t0 + tickIndex * TICK);
        collector = new InMemoryMetricsCollector(clock);
    }

    /** 在当前节拍写入 calls 次调用，其中 failures 次失败。 */
    private void emit(int calls, int failures) {
        long ts = clock.nowMillis();
        for (int i = 0; i < calls; i++) {
            CallStatus status = i < failures ? CallStatus.FAILURE : CallStatus.SUCCESS;
            collector.record(new CallRecordPoint(EP, ts, 1L, status));
        }
    }

    private EvaluationResult eval(String ruleId) {
        MetricSnapshot snapshot = collector.getSnapshot(EP, WINDOW);
        EvaluationResult r = evaluator.evaluate(ruleId, snapshot);
        log.info("[状态机] tick@{}: 输入指标 failureRate={} total={} => 状态={}, breach={}, breachStreak={}, healthyStreak={}, changed={}, replay={} | 依据: {}",
                clock.nowMillis(), r.metricValue(), snapshot.totalCount(),
                r.state(), r.breach(), r.breachStreak(), r.healthyStreak(),
                r.stateChanged(), r.idempotentReplay(), r.reason());
        return r;
    }

    @Test
    void jitterPendingFiringRecoverAndIdempotency() {
        evaluator = new DefaultAlertEvaluator(clock);
        AlertRule rule = new AlertRule("high-failure-rate", EP,
                MetricType.FAILURE_RATE, ComparisonOperator.GREATER_THAN, 0.5d, 3, 2);
        evaluator.registerRule(rule);
        log.info("[状态机] 注册规则: failureRate > 0.5, 连续 3 次确认触发, 连续 2 次健康恢复, 窗口={}ms, tick={}ms",
                WINDOW, TICK);

        // tick1: 命中一次 -> PENDING（单次抖动不触发）
        newTick(1); emit(2, 2);
        EvaluationResult r1 = eval(rule.ruleId());
        assertEquals(AlertState.PENDING, r1.state(), "第一次命中只能进入待确认");
        assertTrue(r1.stateChanged());

        // tick2: 健康一次 -> 回 NOT_TRIGGERED，breachStreak 清零
        newTick(2); emit(8, 0);
        EvaluationResult r2 = eval(rule.ruleId());
        assertEquals(AlertState.NOT_TRIGGERED, r2.state(), "待确认期间恢复健康，应回未触发");

        // tick3-5: 连续 3 次命中 -> PENDING,PENDING,FIRING
        newTick(3); emit(10, 10);
        assertEquals(AlertState.PENDING, eval(rule.ruleId()).state());
        newTick(4); emit(10, 10);
        assertEquals(AlertState.PENDING, eval(rule.ruleId()).state());
        newTick(5); emit(10, 10);
        EvaluationResult r5 = eval(rule.ruleId());
        assertEquals(AlertState.FIRING, r5.state(), "连续命中达到 3 次必须触发");
        assertTrue(r5.stateChanged());

        // tick6: 触发后仅健康一次 -> 仍 FIRING，不得直接解除
        newTick(6); emit(10, 0);
        EvaluationResult r6 = eval(rule.ruleId());
        assertEquals(AlertState.FIRING, r6.state(), "恢复需要稳定，单次健康抖动不得解除告警");
        assertEquals(1, r6.healthyStreak());

        // tick7: 又命中一次 -> 仍 FIRING，healthyStreak 被清零
        newTick(7); emit(10, 10);
        EvaluationResult r7 = eval(rule.ruleId());
        assertEquals(AlertState.FIRING, r7.state());
        assertEquals(0, r7.healthyStreak());

        // tick8-9: 连续 2 次健康 -> FIRING 后 RESOLVED
        newTick(8); emit(10, 0);
        assertEquals(AlertState.FIRING, eval(rule.ruleId()).state());
        newTick(9); emit(10, 0);
        EvaluationResult r9 = eval(rule.ruleId());
        assertEquals(AlertState.RESOLVED, r9.state(), "连续 2 次健康后恢复");
        assertTrue(r9.stateChanged());

        // 幂等：同一节拍重复评估，必须回放且不改状态/streak
        EvaluationResult replay1 = eval(rule.ruleId());
        EvaluationResult replay2 = eval(rule.ruleId());
        assertTrue(replay1.idempotentReplay() && replay2.idempotentReplay(), "重复评估必须标记为幂等回放");
        assertFalse(replay1.stateChanged());
        assertEquals(AlertState.RESOLVED, replay2.state());
        assertEquals(r9.healthyStreak(), replay2.healthyStreak(), "幂等回放不得修改 streak");
        log.info("[状态机] 判定依据: 重复评估两次均 idempotentReplay=true, 状态与 healthyStreak={} 保持不变",
                replay2.healthyStreak());

        // tick10: 恢复后再次抖动 -> PENDING（需重新确认，不能直接 FIRING）
        newTick(10); emit(10, 10);
        EvaluationResult r10 = eval(rule.ruleId());
        assertEquals(AlertState.PENDING, r10.state(), "已恢复后再次异常必须重新走待确认");
    }

    @Test
    void pendingStreakOneFiresImmediately() {
        evaluator = new DefaultAlertEvaluator(clock);
        AlertRule rule = new AlertRule("p95-spike", EP,
                MetricType.P95_LATENCY_MILLIS, ComparisonOperator.GREATER_THAN_OR_EQUAL, 100.0d, 1, 1);
        evaluator.registerRule(rule);
        log.info("[状态机] 注册规则: p95 >= 100ms, pending=1, recover=1");

        newTick(1);
        collector.record(new CallRecordPoint(EP, clock.nowMillis() - 200L, 120L, CallStatus.SUCCESS));
        EvaluationResult r1 = eval(rule.ruleId());
        assertEquals(AlertState.FIRING, r1.state(), "pendingStreak=1 时首次命中即触发");

        newTick(2);
        collector.record(new CallRecordPoint(EP, clock.nowMillis() - 200L, 10L, CallStatus.SUCCESS));
        EvaluationResult r2 = eval(rule.ruleId());
        assertEquals(AlertState.RESOLVED, r2.state(), "recoverStreak=1 时首次健康即恢复");
    }
}
