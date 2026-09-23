package com.github.lisama569.telemetryalertingjava.core;

/**
 * 时钟抽象，便于测试中控制窗口滚动。
 */
@FunctionalInterface
public interface Clock {
    long nowMillis();
}
