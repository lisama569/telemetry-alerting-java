package com.github.lisama569.telemetryalertingjava.metrics;

/**
 * 某接口当前生效的保留上限视图（运行期可查）。
 *
 * @param api                    接口名
 * @param maxSamplesPerApi       该接口当前生效的保留上限（0 表示不限）
 * @param defaultMaxSamplesPerApi 全局默认上限（未单独设置的接口走该值，0 表示不限）
 * @param overridden             该接口是否设置了单独上限（false = 走默认值）
 */
public record CapacityInfo(
        String api,
        long maxSamplesPerApi,
        long defaultMaxSamplesPerApi,
        boolean overridden) {
}
