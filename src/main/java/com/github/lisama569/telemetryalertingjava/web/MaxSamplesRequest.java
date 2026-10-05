package com.github.lisama569.telemetryalertingjava.web;

/**
 * 运行期设置单接口保留上限的请求体。
 *
 * @param maxSamples 保留样本数上限，必须为正数；恢复默认请用 DELETE 端点
 */
public record MaxSamplesRequest(Long maxSamples) {
}
