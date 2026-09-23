package com.github.lisama569.telemetryalertingjava.alerting;

import com.github.lisama569.telemetryalertingjava.model.MetricSnapshot;

/** 告警规则注册与评估入口。 */
public interface AlertEvaluator {

    /** 注册规则；非法规则必须被拒绝并给出可区分原因。 */
    void registerRule(AlertRule rule);

    /** 基于给定窗口快照评估一次规则；同一规则重复评估相同输入必须幂等。 */
    EvaluationResult evaluate(String ruleId, MetricSnapshot snapshot);

    /** 查询规则当前状态实体（快照视图）。 */
    AlertStateEntity getState(String ruleId);

    /** 查询已注册规则；不存在时抛出 {@link com.github.lisama569.telemetryalertingjava.exception.EvaluationException}。 */
    AlertRule getRule(String ruleId);
}
