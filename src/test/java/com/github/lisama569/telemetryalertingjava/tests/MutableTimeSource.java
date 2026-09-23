package com.github.lisama569.telemetryalertingjava.tests;

import com.github.lisama569.telemetryalertingjava.common.TimeSource;

/** 可手动推进的时间源，保证测试结果可复现。 */
public final class MutableTimeSource implements TimeSource {

    private long now;

    public MutableTimeSource(long startMillis) {
        this.now = startMillis;
    }

    @Override
    public long nowMillis() {
        return now;
    }

    public void setTime(long millis) {
        this.now = millis;
    }

    public void advance(long deltaMillis) {
        if (deltaMillis < 0) {
            throw new IllegalArgumentException("不允许把时间往回拨: " + deltaMillis);
        }
        this.now += deltaMillis;
    }
}
