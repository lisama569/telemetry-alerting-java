package com.github.lisama569.telemetryalertingjava.alerting;

/**
 * 规则的运行时状态实体（可变）。
 * 该对象本身不加锁；并发安全由 {@link DefaultAlertEvaluator} 按规则加锁保证。
 */
public final class AlertStateEntity {

    private AlertState state = AlertState.NOT_TRIGGERED;
    private int breachStreak;
    private int healthyStreak;
    private long stateChangedAtMillis;
    private String lastEvaluationSignature;

    public AlertStateEntity() {
    }

    public AlertState getState() {
        return state;
    }

    public int getBreachStreak() {
        return breachStreak;
    }

    public int getHealthyStreak() {
        return healthyStreak;
    }

    public long getStateChangedAtMillis() {
        return stateChangedAtMillis;
    }

    public String getLastEvaluationSignature() {
        return lastEvaluationSignature;
    }

    /** 发生状态迁移时更新状态与时间；未迁移时不应调用。 */
    public void transitionTo(AlertState newState, long nowMillis) {
        this.state = newState;
        this.stateChangedAtMillis = nowMillis;
    }

    public void setStreaks(int breachStreak, int healthyStreak) {
        this.breachStreak = breachStreak;
        this.healthyStreak = healthyStreak;
    }

    public void setLastEvaluationSignature(String signature) {
        this.lastEvaluationSignature = signature;
    }
}
