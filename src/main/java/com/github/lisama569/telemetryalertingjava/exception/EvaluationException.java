package com.github.lisama569.telemetryalertingjava.exception;

/** 规则评估过程中的异常，如引用了未注册规则或缺失指标。 */
public class EvaluationException extends TelemetryException {
    public EvaluationException(String reasonCode, String message) {
        super(reasonCode, message);
    }
}
