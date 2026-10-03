package com.github.lisama569.telemetryalertingjava.metrics;

/**
 * 某接口的容量淘汰统计（仅统计因容量上限被淘汰的量，窗口自然滚动淘汰不计入）。
 *
 * @param maxSamplesPerApi 该接口生效的保留上限（0 表示不限）
 * @param retainedSamples  当前仍保留的样本数，恒满足 {@code retainedSamples <= maxSamplesPerApi}（上限为 0 时除外）
 * @param evictedSamples   历史上因容量被淘汰的样本总数（单调不减）
 * @param evictedBuckets   历史上因容量被整体清空移除的桶数（单调不减）
 */
public record EvictionStats(
        String api,
        long maxSamplesPerApi,
        long retainedSamples,
        long evictedSamples,
        long evictedBuckets) {
}
