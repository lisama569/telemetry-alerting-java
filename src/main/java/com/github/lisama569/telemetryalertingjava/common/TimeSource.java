package com.github.lisama569.telemetryalertingjava.common;

/** 时间源，生产环境使用系统时钟，测试可替换为可控时钟。 */
public interface TimeSource {
    long nowMillis();
}
