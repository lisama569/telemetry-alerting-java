package com.github.lisama569.telemetryalertingjava.alerting;

/**
 * 一次规则评估结果。
 *
 * @param ruleId           规则 id
 * @param state            评估后的状态
 * @param breach           当前指标是否满足触发条件
 * @param breachStreak     当前连续命中次数
 * @param healthyStreak    当前连续健康次数
 * @param metricValue      参与判定的指标值
 * @param stateChanged     本次评估是否发生状态迁移
 * @param idempotentReplay 是否因重复输入幂等跳过
 * @param reason           判定依据的文字说明
 * @param evaluatedAtMillis 评估时间
 */
public record EvaluationResult(String ruleId,
                               AlertState state,
                               boolean breach,
                               int breachStreak,
                               int healthyStreak,
                               double metricValue,
                               boolean stateChanged,
                               boolean idempotentReplay,
                               String reason,
                               long evaluatedAtMillis) {
}
