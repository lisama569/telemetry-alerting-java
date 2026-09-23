package com.github.lisama569.telemetryalertingjava.alert;

/**
 * 非法规则或无法评估（引用未知指标等）时抛出，携带可区分的原因码。
 */
public class InvalidAlertRuleException extends RuntimeException {
    private final String reason;

    public InvalidAlertRuleException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
