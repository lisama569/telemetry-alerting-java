package com.github.lisama569.telemetryalertingjava.web;

import java.util.List;

/**
 * 批量历史补报请求体。
 */
public record BackfillRequest(List<Item> events) {

    public record Item(String api, String status, Long latencyMillis, Long timestampMillis) {
    }
}
