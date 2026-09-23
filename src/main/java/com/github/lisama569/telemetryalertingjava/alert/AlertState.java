package com.github.lisama569.telemetryalertingjava.alert;

/**
 * 告警规则状态：未触发 -> 待确认 -> 触发中 -> 已恢复 -> （再次异常）待确认。
 */
public enum AlertState {
    NOT_TRIGGERED,
    PENDING,
    FIRING,
    RECOVERED
}
