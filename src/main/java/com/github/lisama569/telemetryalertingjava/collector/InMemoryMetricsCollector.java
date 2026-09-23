package com.github.lisama569.telemetryalertingjava.collector;

import com.github.lisama569.telemetryalertingjava.common.TimeSource;
import com.github.lisama569.telemetryalertingjava.exception.InvalidRecordException;
import com.github.lisama569.telemetryalertingjava.exception.TelemetryException;
import com.github.lisama569.telemetryalertingjava.model.CallRecordPoint;
import com.github.lisama569.telemetryalertingjava.model.CallStatus;
import com.github.lisama569.telemetryalertingjava.model.MetricSnapshot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内滚动窗口指标采集器。
 *
 * 并发模型：
 * <ul>
 *   <li>每个接口一个 {@link EndpointData}，内部持有独立锁与按时间戳有序的埋点列表；</li>
 *   <li>写入只在所属接口的锁内追加一次（先校验后写入，非法埋点不产生任何写入），
 *       因此不存在计数丢失或重复计入；</li>
 *   <li>计数全部由窗口内的埋点实时汇总得到，过期埋点只会整条删除，任何指标不可能为负。</li>
 * </ul>
 */
public class InMemoryMetricsCollector implements MetricsCollector {

    private final TimeSource timeSource;
    private final Map<String, EndpointData> endpoints = new ConcurrentHashMap<>();

    public InMemoryMetricsCollector(TimeSource timeSource) {
        if (timeSource == null) {
            throw new TelemetryException("INVALID_TIME_SOURCE", "时间源不允许为 null");
        }
        this.timeSource = timeSource;
    }

    private static final class EndpointData {
        private final Object lock = new Object();
        /** 按 timestampMillis 升序排列；同一时间戳内顺序任意。 */
        private final List<CallRecordPoint> points = new ArrayList<>();
    }

    @Override
    public void record(CallRecordPoint point) {
        if (point == null) {
            throw new InvalidRecordException("INVALID_RECORD_NULL", "埋点不允许为 null");
        }
        long now = timeSource.nowMillis();
        point.validate(now);

        EndpointData data = endpoints.computeIfAbsent(point.endpoint(), k -> new EndpointData());
        synchronized (data.lock) {
            // 再次校验，确保持锁后（如测试中时间被回拨）口径一致
            point.validate(timeSource.nowMillis());
            insertOrdered(data.points, point);
        }
    }

    /** 按时间戳升序插入；多数情况下追加到尾部，O(1)。 */
    private static void insertOrdered(List<CallRecordPoint> points, CallRecordPoint point) {
        int size = points.size();
        if (size == 0 || points.get(size - 1).timestampMillis() <= point.timestampMillis()) {
            points.add(point);
            return;
        }
        int pos = size;
        while (pos > 0 && points.get(pos - 1).timestampMillis() > point.timestampMillis()) {
            pos--;
        }
        points.add(pos, point);
    }

    @Override
    public MetricSnapshot getSnapshot(String endpoint, long windowMillis) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new TelemetryException("INVALID_ENDPOINT_BLANK", "查询接口标识缺失或为空白");
        }
        if (windowMillis <= 0L) {
            throw new TelemetryException("INVALID_WINDOW_MILLIS",
                    "窗口长度必须为正毫秒数: " + windowMillis);
        }
        long now = timeSource.nowMillis();
        long windowStart = now - windowMillis; // 保留 [windowStart, now]
        EndpointData data = endpoints.get(endpoint);
        if (data == null) {
            return emptySnapshot(endpoint, windowMillis, windowStart, now);
        }
        synchronized (data.lock) {
            evictExpired(data.points, windowStart);
            return aggregate(endpoint, windowMillis, windowStart, now, data.points);
        }
    }

    @Override
    public List<MetricSnapshot> getAllSnapshots(long windowMillis) {
        if (windowMillis <= 0L) {
            throw new TelemetryException("INVALID_WINDOW_MILLIS",
                    "窗口长度必须为正毫秒数: " + windowMillis);
        }
        List<MetricSnapshot> result = new ArrayList<>();
        for (Map.Entry<String, EndpointData> entry : endpoints.entrySet()) {
            result.add(getSnapshot(entry.getKey(), windowMillis));
        }
        result.sort(Comparator.comparing(MetricSnapshot::endpoint));
        return result;
    }

    /** 删除时间戳早于 windowStart 的整条埋点；删除不改变其余埋点的计数贡献。 */
    private static void evictExpired(List<CallRecordPoint> points, long windowStart) {
        int removeCount = 0;
        while (removeCount < points.size() && points.get(removeCount).timestampMillis() < windowStart) {
            removeCount++;
        }
        if (removeCount > 0) {
            points.subList(0, removeCount).clear();
        }
    }

    private static MetricSnapshot aggregate(String endpoint, long windowMillis,
                                            long windowStart, long now,
                                            List<CallRecordPoint> allInWindow) {
        long total = 0L;
        long success = 0L;
        long failure = 0L;
        long latencySum = 0L;
        long minLatency = Long.MAX_VALUE;
        long maxLatency = 0L;
        long[] latencies = new long[allInWindow.size()];
        int n = 0;

        for (CallRecordPoint p : allInWindow) {
            // 严格排除落在窗口之外的数据（过期的已在调用前淘汰；此处再排除未来数据）
            if (p.timestampMillis() < windowStart || p.timestampMillis() > now) {
                continue;
            }
            total++;
            latencySum += p.latencyMillis();
            latencies[n++] = p.latencyMillis();
            if (p.latencyMillis() < minLatency) {
                minLatency = p.latencyMillis();
            }
            if (p.latencyMillis() > maxLatency) {
                maxLatency = p.latencyMillis();
            }
            if (p.status() == CallStatus.SUCCESS) {
                success++;
            } else {
                failure++;
            }
        }

        if (total == 0L) {
            return emptySnapshot(endpoint, windowMillis, windowStart, now);
        }

        double successRate = (double) success / (double) total;
        double failureRate = (double) failure / (double) total;
        double avgLatency = (double) latencySum / (double) total;

        long[] sorted = Arrays.copyOf(latencies, n);
        Arrays.sort(sorted);
        return new MetricSnapshot(
                endpoint, windowMillis, windowStart, now,
                total, success, failure,
                round4(successRate), round4(failureRate),
                round4(avgLatency),
                minLatency, maxLatency,
                percentile(sorted, 50),
                percentile(sorted, 95),
                percentile(sorted, 99));
    }

    private static MetricSnapshot emptySnapshot(String endpoint, long windowMillis,
                                                long windowStart, long now) {
        return new MetricSnapshot(endpoint, windowMillis, windowStart, now,
                0L, 0L, 0L, 0.0d, 0.0d, 0.0d, 0L, 0L, 0L, 0L, 0L);
    }

    /** 最近秩百分位（nearest-rank），入参数组必须已升序且非空。 */
    private static long percentile(long[] sortedAsc, int percentile) {
        int n = sortedAsc.length;
        int rank = (int) Math.ceil(percentile / 100.0d * n);
        int index = Math.min(Math.max(rank, 1), n) - 1;
        return sortedAsc[index];
    }

    private static double round4(double value) {
        return Math.round(value * 10000.0d) / 10000.0d;
    }
}
