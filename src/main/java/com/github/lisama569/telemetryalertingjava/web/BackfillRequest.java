package com.github.lisama569.telemetryalertingjava.web;

import java.util.List;

/**
 * 历史补报请求体：一批带事件时间戳的埋点，允许乱序、允许时间戳早于已见数据。
 */
public record BackfillRequest(List<BackfillEvent> events) {

    /**
     * 单条补报事件。status 用字符串承载以便逐条容错（非法条目进回执而不是拖垮整批）；
     * timestampMillis 必填（历史数据必须携带事件时间）。
     */
    public record BackfillEvent(String api, String status, Long latencyMillis,
                                Long timestampMillis) {
    }
}
