package com.github.lisama569.telemetryalertingjava.exception;

/** 指标采集与告警模块的受检异常基类，携带可区分的拒绝原因。 */
public class TelemetryException extends RuntimeException {

    private final String reasonCode;

    public TelemetryException(String reasonCode, String message) {
        super(message);
        this.reasonCode = reasonCode;
    }

    public String getReasonCode() {
        return reasonCode;
    }
}
