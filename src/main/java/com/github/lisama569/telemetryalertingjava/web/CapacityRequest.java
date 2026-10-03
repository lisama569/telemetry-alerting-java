package com.github.lisama569.telemetryalertingjava.web;

/**
 * 设置单接口保留上限的请求体。maxEvents 为 null 或 0 表示不限。
 */
public record CapacityRequest(Long maxEvents) {
}
