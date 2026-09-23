package com.github.lisama569.telemetryalertingjava.web;

/**
 * 统一错误响应：携带可区分的原因码，绝不静默接受非法输入。
 */
public record ErrorResponse(String error, String reason, String message) {
}
