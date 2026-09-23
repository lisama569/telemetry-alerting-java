package com.github.lisama569.telemetryalertingjava.metrics;

import com.github.lisama569.telemetryalertingjava.core.Clock;
import com.github.lisama569.telemetryalertingjava.core.SystemClock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * 进程内接口指标注册表：线程安全的滚动窗口计数器。
 *
 * <p>实现要点：
 * <ul>
 *   <li>窗口被切成若干定宽时间桶（bucket），按事件时间戳归入对应桶；</li>
 *   <li>每个接口有独立锁（{@link ApiWindow} 内置），不同接口之间互不阻塞；</li>
 *   <li>桶内计数只做加法，窗口滚动时只把整桶从队列头部移除，聚合 = 现存桶求和，
 *       因此不存在"先加后减"的中间态，任何时刻读到的计数都不会为负；</li>
 *   <li>记录与取快照都在同一把锁内完成"淘汰过期桶 + 读/写"，并发下无丢失、无重复。</li>
 * </ul>
 */
public class MetricsRegistry {

    /** 拒绝埋点的原因码。 */
    public static final String REASON_BLANK_API = "BLANK_API";
    public static final String REASON_NULL_STATUS = "NULL_STATUS";
    public static final String REASON_NEGATIVE_LATENCY = "NEGATIVE_LATENCY";
    public static final String REASON_LATENCY_TOO_LARGE = "LATENCY_TOO_LARGE";
    public static final String REASON_TIMESTAMP_IN_FUTURE = "TIMESTAMP_IN_FUTURE";
    public static final String REASON_TIMESTAMP_TOO_OLD = "TIMESTAMP_TOO_OLD";
    public static final String REASON_NULL_EVENT = "NULL_EVENT";

    private static final long DEFAULT_WINDOW_MILLIS = 60_000L;
    private static final long DEFAULT_BUCKET_MILLIS = 1_000L;
    /** 延迟上限（24 小时），超过视为脏数据。 */
    private static final long MAX_LATENCY_MILLIS = 24L * 60 * 60 * 1000;

    private final long windowMillis;
    private final long bucketMillis;
    private final Clock clock;
    private final java.util.concurrent.ConcurrentHashMap<String, ApiWindow> apis =
            new java.util.concurrent.ConcurrentHashMap<>();

    public MetricsRegistry() {
        this(DEFAULT_WINDOW_MILLIS, DEFAULT_BUCKET_MILLIS, new SystemClock());
    }

    public MetricsRegistry(long windowMillis, long bucketMillis, Clock clock) {
        if (windowMillis <= 0) {
            throw new IllegalArgumentException("windowMillis must be positive: " + windowMillis);
        }
        if (bucketMillis <= 0 || bucketMillis > windowMillis) {
            throw new IllegalArgumentException(
                    "bucketMillis must be in (0, windowMillis]: " + bucketMillis);
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.windowMillis = windowMillis;
        this.bucketMillis = bucketMillis;
        this.clock = clock;
    }

    /** 记录一次调用（使用注册表时钟）。 */
    public void record(String api, CallStatus status, long latencyMillis) {
        record(api, status, latencyMillis, clock.nowMillis());
    }

    /** 记录一次调用（显式指定事件时间戳，主要用于测试窗口滚动）。 */
    public void record(String api, CallStatus status, long latencyMillis, long timestampMillis) {
        validate(api, status, latencyMillis, timestampMillis);
        ApiWindow window = apis.computeIfAbsent(api, ignored -> new ApiWindow());
        window.add(api, status, latencyMillis, timestampMillis);
    }

    /** 记录一个事件对象。 */
    public void record(CallEvent event) {
        if (event == null) {
            throw new InvalidMetricException(REASON_NULL_EVENT, "call event must not be null");
        }
        record(event.api(), event.status(), event.latencyMillis(), event.timestampMillis());
    }

    /** 取某接口当前窗口聚合；该接口在窗口内无任何数据时返回 empty。 */
    public Optional<WindowSnapshot> snapshot(String api) {
        if (api == null || api.isBlank()) {
            throw new InvalidMetricException(REASON_BLANK_API, "api must not be blank");
        }
        ApiWindow window = apis.get(api);
        if (window == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(window.aggregate(api));
    }

    public long windowMillis() {
        return windowMillis;
    }

    public long bucketMillis() {
        return bucketMillis;
    }

    private void validate(String api, CallStatus status, long latencyMillis, long timestampMillis) {
        if (api == null || api.isBlank()) {
            throw new InvalidMetricException(REASON_BLANK_API, "api must not be blank");
        }
        if (status == null) {
            throw new InvalidMetricException(REASON_NULL_STATUS,
                    "call status must not be null for api=" + api);
        }
        if (latencyMillis < 0) {
            throw new InvalidMetricException(REASON_NEGATIVE_LATENCY,
                    "latencyMillis must be >= 0, got " + latencyMillis + " for api=" + api);
        }
        if (latencyMillis > MAX_LATENCY_MILLIS) {
            throw new InvalidMetricException(REASON_LATENCY_TOO_LARGE,
                    "latencyMillis " + latencyMillis + " exceeds upper bound "
                            + MAX_LATENCY_MILLIS + " for api=" + api);
        }
        long now = clock.nowMillis();
        if (timestampMillis > now) {
            throw new InvalidMetricException(REASON_TIMESTAMP_IN_FUTURE,
                    "timestampMillis " + timestampMillis + " is in the future (now=" + now
                            + ") for api=" + api);
        }
        if (timestampMillis < now - windowMillis) {
            throw new InvalidMetricException(REASON_TIMESTAMP_TOO_OLD,
                    "timestampMillis " + timestampMillis + " is older than the window start "
                            + (now - windowMillis) + " for api=" + api);
        }
    }

    /**
     * 单个接口的滚动窗口。所有字段仅在持有实例锁时访问。
     */
    private final class ApiWindow {
        private final Deque<Bucket> buckets = new ArrayDeque<>();

        synchronized void add(String api, CallStatus status, long latency, long timestamp) {
            long now = clock.nowMillis();
            long bucketStart = floorToBucket(timestamp);
            // 桶覆盖 [bucketStart, bucketStart+bucket)；其结束位置已越过/触及当前时间，
            // 说明事件时间戳超过了时钟（理论上 validate 已拦截，这里防御性拒绝，绝不静默计入未来）
            if (bucketStart > floorToBucket(now)
                    || (bucketStart == floorToBucket(now) && timestamp > now)) {
                throw new InvalidMetricException(REASON_TIMESTAMP_IN_FUTURE,
                        "timestampMillis " + timestamp + " is in the future (now=" + now
                                + ") for api=" + api);
            }
            evictExpired(now);
            // 过期时间戳（校验之后又恰好被滚动出去）直接拒绝，不静默丢弃也不放行。
            if (bucketStart + bucketMillis <= now - windowMillis) {
                throw new InvalidMetricException(REASON_TIMESTAMP_TOO_OLD,
                        "timestampMillis " + timestamp + " already rolled out of the window for api="
                                + api);
            }
            Bucket bucket = buckets.peekLast();
            if (bucket == null) {
                bucket = new Bucket(bucketStart);
                buckets.addLast(bucket);
            } else if (bucketStart > bucket.startMillis) {
                bucket = new Bucket(bucketStart);
                buckets.addLast(bucket);
            } else if (bucketStart < bucket.startMillis) {
                // 乱序事件落在已跳过/更早的桶里，回填会破坏桶按序滚动的不变式，明确拒绝
                throw new InvalidMetricException(REASON_TIMESTAMP_TOO_OLD,
                        "out-of-order timestampMillis " + timestamp
                                + " cannot be backfilled into an earlier bucket for api=" + api);
            }
            bucket.total++;
            if (status == CallStatus.SUCCESS) {
                bucket.success++;
            } else {
                bucket.failure++;
            }
            bucket.latencySum += latency;
            if (latency > bucket.maxLatency) {
                bucket.maxLatency = latency;
            }
            bucket.latencies.add(latency);
        }

        synchronized WindowSnapshot aggregate(String api) {
            long now = clock.nowMillis();
            evictExpired(now);
            long windowStart = now - windowMillis;
            long total = 0L;
            long success = 0L;
            long failure = 0L;
            long latencySum = 0L;
            long maxLatency = 0L;
            List<Long> latencies = new ArrayList<>();
            for (Bucket b : buckets) {
                total += b.total;
                success += b.success;
                failure += b.failure;
                latencySum += b.latencySum;
                maxLatency = Math.max(maxLatency, b.maxLatency);
                latencies.addAll(b.latencies);
            }
            // 桶全部滚出窗口后不再返回带数据的快照（交由调用方视为"窗口内无指标"）
            if (total == 0L) {
                return null;
            }
            latencies.sort(Long::compareTo);
            double successRate = total == 0 ? 0d : (double) success / total;
            double failureRate = total == 0 ? 0d : (double) failure / total;
            double avgLatency = total == 0 ? 0d : (double) latencySum / total;
            return new WindowSnapshot(api, windowStart, now, total, success, failure,
                    successRate, failureRate, avgLatency, maxLatency, latencies);
        }

        /**
         * 淘汰与窗口 {@code [now-window, now)} 不再相交的桶，调用方须持锁。
         * 桶覆盖 [start, start+bucket)，其结束时刻不大于窗口左界即整体过期。
         */
        private void evictExpired(long now) {
            long windowLeft = now - windowMillis;
            while (true) {
                Bucket oldest = buckets.peekFirst();
                if (oldest == null || oldest.startMillis + bucketMillis > windowLeft) {
                    break;
                }
                buckets.pollFirst();
            }
        }

        private long floorToBucket(long millis) {
            return Math.floorDiv(millis, bucketMillis) * bucketMillis;
        }
    }

    /**
     * 定宽时间桶。字段为包可见并仅在持锁时读写；计数恒为非负。
     */
    private static final class Bucket {
        private final long startMillis;
        private long total;
        private long success;
        private long failure;
        private long latencySum;
        private long maxLatency;
        private final List<Long> latencies = new ArrayList<>();

        private Bucket(long startMillis) {
            this.startMillis = startMillis;
        }
    }
}
