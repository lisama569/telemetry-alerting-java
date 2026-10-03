package com.github.lisama569.telemetryalertingjava.metrics;

import com.github.lisama569.telemetryalertingjava.core.Clock;
import com.github.lisama569.telemetryalertingjava.core.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 进程内接口指标注册表：线程安全的滚动窗口计数器。
 *
 * <p>实现要点：
 * <ul>
 *   <li>窗口被切成若干定宽时间桶（bucket），按事件时间戳归入对应桶；每个接口的桶放在
 *       {@link TreeMap} 里按桶起点排序，因此历史补报可以插入到任意已存在的中间桶；</li>
 *   <li>桶内直接保存事件本身（{@link CallEvent}），聚合 = 对现存桶内事件现算求和，
 *       计数只有单一数据源，无论是窗口滚动淘汰还是容量淘汰，任何时刻读到的计数都不会为负；</li>
 *   <li>每个接口有独立锁（{@link ApiWindow} 内置），不同接口之间互不阻塞；
 *       补报按事件逐条短临界区写入，不会长时间卡住同一接口的实时写入；</li>
 *   <li>每接口可设保留样本上限 {@code maxSamplesPerApi}（0 = 不限），超限后按
 *       "最旧样本优先"的固定规则淘汰，被淘汰的量通过 {@link #evictionStats(String)} 单独暴露。</li>
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
    /** 补报事件缺少时间戳（历史数据必须携带事件时间）。 */
    public static final String REASON_NULL_TIMESTAMP = "NULL_TIMESTAMP";

    private static final long DEFAULT_WINDOW_MILLIS = 60_000L;
    private static final long DEFAULT_BUCKET_MILLIS = 1_000L;
    /** 延迟上限（24 小时），超过视为脏数据。 */
    private static final long MAX_LATENCY_MILLIS = 24L * 60 * 60 * 1000;

    private final long windowMillis;
    private final long bucketMillis;
    private final Clock clock;
    /** 每接口保留样本数上限，0 表示不限（默认，兼容既有行为）。 */
    private final long maxSamplesPerApi;
    private final java.util.concurrent.ConcurrentHashMap<String, ApiWindow> apis =
            new java.util.concurrent.ConcurrentHashMap<>();

    public MetricsRegistry() {
        this(DEFAULT_WINDOW_MILLIS, DEFAULT_BUCKET_MILLIS, new SystemClock());
    }

    public MetricsRegistry(long windowMillis, long bucketMillis, Clock clock) {
        this(windowMillis, bucketMillis, clock, 0L);
    }

    public MetricsRegistry(long windowMillis, long bucketMillis, Clock clock,
                           long maxSamplesPerApi) {
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
        if (maxSamplesPerApi < 0) {
            throw new IllegalArgumentException(
                    "maxSamplesPerApi must be >= 0 (0 means unlimited): " + maxSamplesPerApi);
        }
        this.windowMillis = windowMillis;
        this.bucketMillis = bucketMillis;
        this.clock = clock;
        this.maxSamplesPerApi = maxSamplesPerApi;
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

    /**
     * 历史补报：批量写入时间戳可能早于已见数据、批内顺序任意的事件。
     *
     * <p>口径与实时写入完全一致：同样落在 {@code [now-window, now]} 内才接受，
     * 校验规则与原因码也和 {@link #record} 相同；区别在于补报允许乱序回填到更早的桶，
     * 且单条非法不会中断整批——非法条目记入回执的 {@code rejections}。
     * 补报完成后，同一窗口的聚合结果与这些事件当时就近实时写入的结果一致。
     *
     * <p>每条事件在独立的短临界区内写入（接口级锁），批量大也不会长时间阻塞同一接口的实时写入。
     *
     * @return 回执：收了多少、接受多少、落在哪些 (接口, 桶)、哪些条目因何被拒
     */
    public BackfillReport backfill(List<CallEvent> events) {
        if (events == null) {
            throw new InvalidMetricException(REASON_NULL_EVENT, "backfill events must not be null");
        }
        int accepted = 0;
        List<BackfillReport.Rejection> rejections = new ArrayList<>();
        Map<String, TreeMap<Long, Long>> slots = new TreeMap<>();
        for (int i = 0; i < events.size(); i++) {
            CallEvent event = events.get(i);
            if (event == null) {
                rejections.add(new BackfillReport.Rejection(
                        i, null, REASON_NULL_EVENT, "call event must not be null"));
                continue;
            }
            try {
                validate(event.api(), event.status(), event.latencyMillis(),
                        event.timestampMillis());
                ApiWindow window = apis.computeIfAbsent(event.api(), ignored -> new ApiWindow());
                long bucketStart = window.addBackfill(event);
                slots.computeIfAbsent(event.api(), ignored -> new TreeMap<>())
                        .merge(bucketStart, 1L, Long::sum);
                accepted++;
            } catch (InvalidMetricException ex) {
                rejections.add(new BackfillReport.Rejection(
                        i, event.api(), ex.reason(), ex.getMessage()));
            }
        }
        List<BackfillReport.Slot> slotList = new ArrayList<>();
        slots.forEach((api, byBucket) -> byBucket.forEach(
                (bucketStart, count) -> slotList.add(
                        new BackfillReport.Slot(api, bucketStart, count))));
        return new BackfillReport(events.size(), accepted, slotList, rejections);
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

    /**
     * 按时间点回看：返回历史时刻 {@code timestampMillis} 所在窗口
     * {@code [timestampMillis-window, timestampMillis]} 的聚合。
     *
     * <p>只统计事件时间戳不超过该时刻的数据，之后新写入（事件时间更晚）不会影响结果；
     * 窗口左界与实时口径一致，按桶粒度对齐。该时刻窗口内的数据已随时间滚动/容量淘汰
     * 销毁后，返回空 {@code Optional}，与"窗口内无指标"一致；同一时刻反复查询结果稳定。
     */
    public Optional<WindowSnapshot> snapshotAt(String api, long timestampMillis) {
        if (api == null || api.isBlank()) {
            throw new InvalidMetricException(REASON_BLANK_API, "api must not be blank");
        }
        long now = clock.nowMillis();
        if (timestampMillis > now) {
            throw new InvalidMetricException(REASON_TIMESTAMP_IN_FUTURE,
                    "snapshotAt timestampMillis " + timestampMillis + " is in the future (now="
                            + now + ") for api=" + api);
        }
        ApiWindow window = apis.get(api);
        if (window == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(window.aggregateAt(api, timestampMillis));
    }

    /**
     * 查询某接口的容量淘汰统计。接口从未上报过时返回全零统计（上限值照常带出）。
     * 只统计因容量上限被淘汰的量，窗口随时间自然滚动淘汰的不计入。
     */
    public EvictionStats evictionStats(String api) {
        if (api == null || api.isBlank()) {
            throw new InvalidMetricException(REASON_BLANK_API, "api must not be blank");
        }
        ApiWindow window = apis.get(api);
        if (window == null) {
            return new EvictionStats(api, maxSamplesPerApi, 0L, 0L, 0L);
        }
        return window.evictionStats(api);
    }

    public long windowMillis() {
        return windowMillis;
    }

    public long bucketMillis() {
        return bucketMillis;
    }

    public long maxSamplesPerApi() {
        return maxSamplesPerApi;
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
        /** 桶起点 -> 桶，按时间升序；补报可插入任意位置。 */
        private final TreeMap<Long, Bucket> buckets = new TreeMap<>();
        /** 当前保留的样本总数（= 各桶事件数之和），窗口滚动与容量淘汰都会扣减。 */
        private long totalSamples;
        /** 因容量上限被淘汰的样本数 / 被整体清空的桶数（单调不减，自然滚动不计入）。 */
        private long evictedSamples;
        private long evictedBuckets;

        /** 实时写入：拒绝乱序回填（保持既有行为），返回所属桶起点。 */
        synchronized long add(String api, CallStatus status, long latency, long timestamp) {
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
            if (!buckets.isEmpty() && bucketStart < buckets.lastKey()) {
                // 实时通道不接受乱序回填（乱序历史数据请走 backfill），保持既有拒绝语义
                throw new InvalidMetricException(REASON_TIMESTAMP_TOO_OLD,
                        "out-of-order timestampMillis " + timestamp
                                + " cannot be backfilled into an earlier bucket for api=" + api);
            }
            putEvent(bucketStart, CallEvent.of(api, status, latency, timestamp));
            return bucketStart;
        }

        /** 补报写入：允许落入任意仍在窗口内的历史桶，返回所属桶起点。 */
        synchronized long addBackfill(CallEvent event) {
            long now = clock.nowMillis();
            long bucketStart = floorToBucket(event.timestampMillis());
            if (bucketStart > floorToBucket(now)
                    || (bucketStart == floorToBucket(now) && event.timestampMillis() > now)) {
                throw new InvalidMetricException(REASON_TIMESTAMP_IN_FUTURE,
                        "timestampMillis " + event.timestampMillis() + " is in the future (now="
                                + now + ") for api=" + event.api());
            }
            evictExpired(now);
            if (bucketStart + bucketMillis <= now - windowMillis) {
                throw new InvalidMetricException(REASON_TIMESTAMP_TOO_OLD,
                        "timestampMillis " + event.timestampMillis()
                                + " already rolled out of the window for api=" + event.api());
            }
            putEvent(bucketStart, event);
            return bucketStart;
        }

        /** 事件入桶（必要时新建桶）并执行容量淘汰，调用方须持锁。 */
        private void putEvent(long bucketStart, CallEvent event) {
            buckets.computeIfAbsent(bucketStart, Bucket::new).events.addLast(event);
            totalSamples++;
            enforceCapacity();
        }

        synchronized WindowSnapshot aggregate(String api) {
            long now = clock.nowMillis();
            evictExpired(now);
            long windowStart = now - windowMillis;
            List<CallEvent> events = new ArrayList<>();
            for (Bucket b : buckets.values()) {
                events.addAll(b.events);
            }
            // 桶全部滚出窗口后不再返回带数据的快照（交由调用方视为"窗口内无指标"）
            if (events.isEmpty()) {
                return null;
            }
            return buildSnapshot(api, windowStart, now, events);
        }

        /**
         * 回看聚合：只读、不做任何淘汰；统计与 {@code [t-window, t]} 相交的桶中
         * 事件时间戳不超过 t 的事件。窗口左界与实时口径一致（桶粒度）。
         */
        synchronized WindowSnapshot aggregateAt(String api, long t) {
            long windowStart = t - windowMillis;
            List<CallEvent> events = new ArrayList<>();
            for (Map.Entry<Long, Bucket> e : buckets.entrySet()) {
                long start = e.getKey();
                if (start + bucketMillis <= windowStart || start > t) {
                    continue;
                }
                for (CallEvent event : e.getValue().events) {
                    if (event.timestampMillis() <= t) {
                        events.add(event);
                    }
                }
            }
            if (events.isEmpty()) {
                return null;
            }
            return buildSnapshot(api, windowStart, t, events);
        }

        synchronized EvictionStats evictionStats(String api) {
            return new EvictionStats(api, maxSamplesPerApi, totalSamples,
                    evictedSamples, evictedBuckets);
        }

        /**
         * 容量淘汰：保留样本数超过上限时，按"最旧样本优先"逐条弹出
         * （时间最早的桶、桶内最早写入的事件），空桶整体移除。
         * 只减真实存在的事件，计数恒为非负。
         */
        private void enforceCapacity() {
            if (maxSamplesPerApi <= 0) {
                return;
            }
            while (totalSamples > maxSamplesPerApi) {
                Bucket oldest = buckets.firstEntry().getValue();
                oldest.events.pollFirst();
                totalSamples--;
                evictedSamples++;
                if (oldest.events.isEmpty()) {
                    buckets.pollFirstEntry();
                    evictedBuckets++;
                }
            }
        }

        /**
         * 淘汰与窗口 {@code [now-window, now)} 不再相交的桶，调用方须持锁。
         * 桶覆盖 [start, start+bucket)，其结束时刻不大于窗口左界即整体过期。
         * 自然滚动淘汰只扣减保留量，不计入容量淘汰统计。
         */
        private void evictExpired(long now) {
            long windowLeft = now - windowMillis;
            while (!buckets.isEmpty()
                    && buckets.firstKey() + bucketMillis <= windowLeft) {
                totalSamples -= buckets.pollFirstEntry().getValue().events.size();
            }
        }

        private long floorToBucket(long millis) {
            return Math.floorDiv(millis, bucketMillis) * bucketMillis;
        }
    }

    /** 由事件集合现算聚合快照，计数单一数据源，任何路径都不会出现负值。 */
    private WindowSnapshot buildSnapshot(String api, long windowStart, long windowEnd,
                                         List<CallEvent> events) {
        long total = 0L;
        long success = 0L;
        long failure = 0L;
        long latencySum = 0L;
        long maxLatency = 0L;
        List<Long> latencies = new ArrayList<>(events.size());
        for (CallEvent event : events) {
            total++;
            if (event.status() == CallStatus.SUCCESS) {
                success++;
            } else {
                failure++;
            }
            latencySum += event.latencyMillis();
            if (event.latencyMillis() > maxLatency) {
                maxLatency = event.latencyMillis();
            }
            latencies.add(event.latencyMillis());
        }
        latencies.sort(Long::compareTo);
        double successRate = total == 0 ? 0d : (double) success / total;
        double failureRate = total == 0 ? 0d : (double) failure / total;
        double avgLatency = total == 0 ? 0d : (double) latencySum / total;
        return new WindowSnapshot(api, windowStart, windowEnd, total, success, failure,
                successRate, failureRate, avgLatency, maxLatency, latencies);
    }

    /**
     * 定宽时间桶：保存落在 {@code [startMillis, startMillis+bucket)} 内的事件，
     * 按写入顺序排列（容量淘汰从头部弹出最旧事件）。
     */
    private static final class Bucket {
        private final long startMillis;
        private final java.util.ArrayDeque<CallEvent> events = new java.util.ArrayDeque<>();

        private Bucket(long startMillis) {
            this.startMillis = startMillis;
        }
    }
}
