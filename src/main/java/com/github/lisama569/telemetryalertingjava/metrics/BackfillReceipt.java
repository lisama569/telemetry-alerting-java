package com.github.lisama569.telemetryalertingjava.metrics;

import java.util.List;
import java.util.Map;

/**
 * 历史补报回执：本次补报收了多少、拒绝了多少（含原因）、分别落在哪些时间桶。
 *
 * @param acceptedCount      被接受并计入的事件数
 * @param rejectedCount      被拒绝的事件数
 * @param rejections         逐条拒绝明细（事件下标 + 原因码 + 说明）
 * @param acceptedByBucket   每个接口下，落入各时间桶（桶起始毫秒）的事件数
 */
public record BackfillReceipt(
        int acceptedCount,
        int rejectedCount,
        List<Rejection> rejections,
        Map<String, Map<Long, Integer>> acceptedByBucket) {

    /** 单条被拒绝的补报事件。 */
    public record Rejection(int index, String api, String reason, String message) {
    }
}
