package com.github.lisama569.telemetryalertingjava.core;

/**
 * 基于系统时间的时钟。
 */
public final class SystemClock implements Clock {
    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}
