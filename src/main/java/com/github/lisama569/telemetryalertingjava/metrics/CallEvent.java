package com.github.lisama569.telemetryalertingjava.metrics;

/**
 * 一次接口调用埋点。
 */
public record CallEvent(String api, CallStatus status, long latencyMillis, long timestampMillis) {
    public static CallEvent of(String api, CallStatus status, long latencyMillis, long timestampMillis) {
        return new CallEvent(api, status, latencyMillis, timestampMillis);
    }
}
