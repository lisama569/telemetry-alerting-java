package com.github.lisama569.telemetryalertingjava.web;

/**
 * 埋点上报请求体。
 */
public record RecordRequest(String api, String status, Long latencyMillis) {
}
