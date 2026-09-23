package com.github.lisama569.telemetryalertingjava.common;

/** 基于 {@link System#currentTimeMillis()} 的默认时间源。 */
public final class SystemTimeSource implements TimeSource {
    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}
