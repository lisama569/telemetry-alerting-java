package com.github.lisama569.telemetryalertingjava.exception;

/** 非法调用埋点异常，原因码以 INVALID_RECORD_ 前缀区分。 */
public class InvalidRecordException extends TelemetryException {
    public InvalidRecordException(String reasonCode, String message) {
        super(reasonCode, message);
    }
}
