package com.github.lisama569.telemetryalertingjava.tests;

import com.github.lisama569.telemetryalertingjava.core.FixedClock;
import com.github.lisama569.telemetryalertingjava.metrics.BackfillReceipt;
import com.github.lisama569.telemetryalertingjava.metrics.CallEvent;
import com.github.lisama569.telemetryalertingjava.metrics.CallStatus;
import com.github.lisama569.telemetryalertingjava.metrics.MetricsRegistry;
import com.github.lisama569.telemetryalertingjava.metrics.WindowSnapshot;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 容量约束测试：保留上限、最旧先淘汰、淘汰量单独暴露、无负值、与补报并存。
 */
class CapacityEvictionTest {

    private static final Logger log = LoggerFactory.getLogger(CapacityEvictionTest.class);

    /** 10s 窗口、1s 桶、60s 保留期。 */
    private MetricsRegistry newRegistry(FixedClock clock) {
        return new MetricsRegistry(10_000L, 1_000L, 60_000L, 0L, clock);
    }

    private void logSnapshot(String title, WindowSnapshot s) {
        log.info("{} | api={} total={} success={} failure={} latencies={}",
                title, s.api(), s.callCount(), s.successCount(), s.failureCount(),
                s.sortedLatenciesMillis());
    }

    @Test
    void capacityEvictsOldestFirstAndExposesEvictedCount() {
        FixedClock clock = new FixedClock(100_000L);
        MetricsRegistry registry = newRegistry(clock);
        registry.setMaxEvents("/a", 5L);

        // 输入 8 条：t=92s..99s 各 1 条，延迟 = 条序号（1..8），交替成功/失败
        for (int i = 1; i <= 8; i++) {
            registry.record("/a", i % 2 == 0 ? CallStatus.SUCCESS : CallStatus.FAILURE,
                    i * 10L, 92_000L + i * 1_000L);
        }
        log.info("输入 8 条（延迟 10..80，t=93s..99s 每秒一条），上限 5 条");

        WindowSnapshot s = registry.snapshot("/a").orElseThrow();
        logSnapshot("容量收敛后聚合", s);
        log.info("判定依据: retained={} <= 上限 5, evicted={} == 输入 8 - retained {}",
                registry.retainedCount("/a"), registry.evictedCount("/a"),
                registry.retainedCount("/a"));

        assertAll("容量口径",
                () -> assertEquals(5L, registry.retainedCount("/a"), "保留数收敛到上限"),
                () -> assertEquals(3L, registry.evictedCount("/a"), "淘汰量单独暴露"),
                () -> assertEquals(5L, s.callCount()),
                // 最旧 3 条（延迟 10,20,30）被淘汰，留下 40..80
                () -> assertEquals(List.of(40L, 50L, 60L, 70L, 80L), s.sortedLatenciesMillis(),
                        "按时间戳最旧先淘汰"),
                () -> assertEquals(s.callCount(), s.successCount() + s.failureCount()),
                () -> assertTrue(s.callCount() >= 0 && s.successCount() >= 0
                        && s.failureCount() >= 0, "淘汰过程不得出现负值"));
    }

    @Test
    void capacityAndBackfillTogetherKeepInvariants() {
        FixedClock clock = new FixedClock(100_000L);
        MetricsRegistry registry = newRegistry(clock);
        registry.setMaxEvents("/b", 10L);

        // 实时写满上限：10 条 t=95s..95.9s
        for (int i = 0; i < 10; i++) {
            registry.record("/b", CallStatus.SUCCESS, 100L + i, 95_000L + i * 100L);
        }
        assertEquals(10L, registry.retainedCount("/b"));
        log.info("实时写入 10 条达到上限，retained=10 evicted={}", registry.evictedCount("/b"));

        // 批量补报 20 条更老的历史数据（t=90s..94.5s，乱序）：全部接受入桶，
        // 但容量淘汰按"最旧先淘汰"会把这些更老的补报数据优先淘汰出去
        List<CallEvent> batch = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            batch.add(CallEvent.of("/b", CallStatus.FAILURE, 10L + i,
                    90_000L + (i * 613L) % 4_500L));
        }
        BackfillReceipt receipt = registry.backfill(batch);
        log.info("补报回执: accepted={} rejected={}", receipt.acceptedCount(),
                receipt.rejectedCount());
        WindowSnapshot s = registry.snapshot("/b").orElseThrow();
        logSnapshot("补报+容量收敛后聚合", s);
        log.info("判定依据: retained={} <= 10, evicted={} == 总入桶 {} - retained {}",
                registry.retainedCount("/b"), registry.evictedCount("/b"),
                10L + receipt.acceptedCount(), registry.retainedCount("/b"));

        assertAll("补报与容量淘汰并存",
                () -> assertEquals(20, receipt.acceptedCount(), "补报全部接受（入桶后再由容量收敛）"),
                () -> assertEquals(10L, registry.retainedCount("/b"), "保留数不超过上限"),
                () -> assertEquals(10L + 20L - 10L, registry.evictedCount("/b"),
                        "淘汰量 = 总入桶 - 保留，账平"),
                () -> assertEquals(10L, s.callCount()),
                () -> assertEquals(s.callCount(), s.successCount() + s.failureCount()),
                () -> assertEquals(s.callCount(), s.sortedLatenciesMillis().size(),
                        "延迟样本数与计数一致，不多不少"),
                () -> assertTrue(registry.evictedCount("/b") >= 0 && s.callCount() >= 0,
                        "任何计数不得为负"));
    }

    @Test
    void capacityZeroMeansUnlimitedAndOverrideIsPerApi() {
        FixedClock clock = new FixedClock(100_000L);
        MetricsRegistry registry = newRegistry(clock);
        registry.setMaxEvents("/capped", 3L);
        // /uncapped 不设上限

        for (int i = 0; i < 10; i++) {
            registry.record("/capped", CallStatus.SUCCESS, 10L, 99_000L + i);
            registry.record("/uncapped", CallStatus.SUCCESS, 10L, 99_000L + i);
        }
        // 上限调回 0 = 不限
        registry.setMaxEvents("/capped", 0L);
        for (int i = 0; i < 5; i++) {
            registry.record("/capped", CallStatus.SUCCESS, 10L, 99_100L + i);
        }
        log.info("/capped 上限 3 -> retained={} evicted={}；取消上限后再写 5 条 -> retained={}；"
                        + "/uncapped retained={}",
                3, 7, registry.retainedCount("/capped"), registry.retainedCount("/uncapped"));

        assertAll("每接口独立上限",
                () -> assertEquals(8L, registry.retainedCount("/capped"),
                        "取消上限后新写入不再被淘汰（3 保留 + 5 新写）"),
                () -> assertEquals(7L, registry.evictedCount("/capped"), "历史淘汰数保留可查"),
                () -> assertEquals(10L, registry.retainedCount("/uncapped"), "未设上限接口不受影响"),
                () -> assertEquals(0L, registry.evictedCount("/uncapped")));
    }
}
