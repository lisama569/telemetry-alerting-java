package com.github.lisama569.telemetryalertingjava.core;

/**
 * 可手动推进的时钟，仅用于测试以保证结果可复现。
 */
public final class FixedClock implements Clock {
    private volatile long now;

    public FixedClock(long initialMillis) {
        this.now = initialMillis;
    }

    @Override
    public long nowMillis() {
        return now;
    }

    public void setMillis(long millis) {
        this.now = millis;
    }

    public void advanceMillis(long delta) {
        this.now += delta;
    }
}
