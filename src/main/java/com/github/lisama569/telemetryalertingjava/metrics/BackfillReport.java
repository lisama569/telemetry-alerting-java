package com.github.lisama569.telemetryalertingjava.metrics;

import java.util.List;

/**
 * 一次历史补报（{@link MetricsRegistry#backfill(List)}）的回执。
 *
 * @param received   本批收到的事件总数
 * @param accepted   被接受并落入窗口的事件数
 * @param slots      接受事件按 (接口, 桶起点) 汇总的落点明细，按接口名、桶起点升序
 * @param rejections 被拒绝事件的明细（含原始下标与原因码），不静默丢弃
 */
public record BackfillReport(
        int received,
        int accepted,
        List<Slot> slots,
        List<Rejection> rejections) {

    public BackfillReport {
        slots = slots == null ? List.of() : List.copyOf(slots);
        rejections = rejections == null ? List.of() : List.copyOf(rejections);
    }

    /** 被拒绝的事件数。 */
    public int rejected() {
        return rejections.size();
    }

    /** 接受事件的落点：某接口某个时间桶（窗口）收了多少条。 */
    public record Slot(String api, long bucketStartMillis, long acceptedCount) {
    }

    /** 单条被拒绝的事件：原始下标 + 原因码（与实时写入的拒绝原因码一致）。 */
    public record Rejection(int index, String api, String reason, String message) {
    }
}
