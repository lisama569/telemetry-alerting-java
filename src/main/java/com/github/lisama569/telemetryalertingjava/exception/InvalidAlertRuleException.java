package com.github.lisama569.telemetryalertingjava.exception;

/** 非法告警规则异常，原因码以 INVALID_RULE_ 前缀区分。 */
public class InvalidAlertRuleException extends TelemetryException {
    public InvalidAlertRuleException(String reasonCode, String message) {
        super(reasonCode, message);
    }
}
