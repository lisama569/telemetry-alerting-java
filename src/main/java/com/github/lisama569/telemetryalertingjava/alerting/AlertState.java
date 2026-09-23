package com.github.lisama569.telemetryalertingjava.alerting;

/**
 * 告警状态：
 * NOT_TRIGGERED 未触发 -> PENDING 待确认 -> FIRING 触发中 -> RESOLVED 已恢复。
 */
public enum AlertState {
    NOT_TRIGGERED,
    PENDING,
    FIRING,
    RESOLVED
}
