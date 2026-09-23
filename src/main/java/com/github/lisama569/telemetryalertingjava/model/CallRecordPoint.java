package com.github.lisama569.telemetryalertingjava.model;

import com.github.lisama569.telemetryalertingjava.exception.InvalidRecordException;

/**
 * 一次接口调用埋点。
 *
 * @param endpoint        接口标识，不允许空白
 * @param timestampMillis 调用时间戳（毫秒），必须为正且不得晚于当前时间
 * @param latencyMillis   延迟（毫秒），不允许为负且不得超过"当前时间-时间戳"
 * @param status          调用结果，不允许为 null
 */
public record CallRecordPoint(String endpoint, long timestampMillis, long latencyMillis, CallStatus status) {

    /** 允许的时钟偏差（毫秒），避免同毫秒写入被误判。 */
    static final long CLOCK_SKEW_TOLERANCE_MILLIS = 5L;

    /** 校验埋点合法性，非法时抛出带原因的异常，且不得产生任何统计写入。 */
    public void validate(long nowMillis) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new InvalidRecordException("INVALID_RECORD_ENDPOINT_BLANK",
                    "接口标识缺失或为空白");
        }
        if (timestampMillis <= 0L) {
            throw new InvalidRecordException("INVALID_RECORD_TIMESTAMP_MISSING",
                    "时间戳缺失或非正: " + timestampMillis);
        }
        if (timestampMillis > nowMillis + CLOCK_SKEW_TOLERANCE_MILLIS) {
            throw new InvalidRecordException("INVALID_RECORD_TIMESTAMP_FUTURE",
                    "时间戳指向未来: " + timestampMillis + " > now " + nowMillis);
        }
        if (latencyMillis < 0L) {
            throw new InvalidRecordException("INVALID_RECORD_LATENCY_NEGATIVE",
                    "延迟不允许为负: " + latencyMillis);
        }
        if (latencyMillis > nowMillis - timestampMillis + CLOCK_SKEW_TOLERANCE_MILLIS) {
            throw new InvalidRecordException("INVALID_RECORD_LATENCY_OUT_OF_RANGE",
                    "延迟超过调用发生以来的 elapsed 时间: latency=" + latencyMillis
                            + ", timestamp=" + timestampMillis + ", now=" + nowMillis);
        }
        if (status == null) {
            throw new InvalidRecordException("INVALID_RECORD_STATUS_MISSING",
                    "调用状态缺失: endpoint=" + endpoint);
        }
    }
}
