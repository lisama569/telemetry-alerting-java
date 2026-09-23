package com.github.lisama569.telemetryalertingjava.metrics;

/**
 * 非法埋点被拒绝时抛出，携带可区分的原因码。
 */
public class InvalidMetricException extends RuntimeException {
    private final String reason;

    public InvalidMetricException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
