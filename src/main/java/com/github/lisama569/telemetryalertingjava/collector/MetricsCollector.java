package com.github.lisama569.telemetryalertingjava.collector;

import com.github.lisama569.telemetryalertingjava.model.CallRecordPoint;
import com.github.lisama569.telemetryalertingjava.model.MetricSnapshot;

import java.util.List;

/**
 * 进程内接口调用指标采集器。
 * 实现需保证：并发写入无计数丢失/重复；窗口滚动后过期数据被淘汰；任何指标不为负。
 */
public interface MetricsCollector {

    /** 记录一次调用；非法埋点必须拒绝且不得产生任何统计写入。 */
    void record(CallRecordPoint point);

    /** 获取某接口在当前滚动窗口内的聚合快照；无数据时返回零值快照。 */
    MetricSnapshot getSnapshot(String endpoint, long windowMillis);

    /** 获取全部已有接口的快照。 */
    List<MetricSnapshot> getAllSnapshots(long windowMillis);
}
