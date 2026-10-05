package com.github.lisama569.telemetryalertingjava.web;

/**
 * 设置单接口保留上限的请求体。maxSamples 必须为正数（0/负数由核心层以 INVALID_CAPACITY 拒绝）。
 */
public record CapacityRequest(Long maxSamples) {
}
