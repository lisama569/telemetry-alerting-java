package com.github.lisama569.telemetryalertingjava.metrics;

import com.github.lisama569.telemetryalertingjava.core.Clock;
import com.github.lisama569.telemetryalertingjava.core.SystemClock;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内接口指标注册表：线程安全的滚动窗口计数器。
 *
 * <p>实现要点：
 * <ul>
 *   <li>窗口被切成若干定宽时间桶（bucket），按事件时间戳归入对应桶；桶存放在
 *       {@link TreeMap} 中，乱序/历史事件可以回填到任意仍保留的桶；</li>
 *   <li>桶内逐事件保存（按时间戳有序），聚合计数永远由现存事件现算，
 *       不存在"先加后减"的中间态，任何时刻读到的计数都不会为负；</li>
 *   <li>每个接口有独立锁（{@link ApiWindow} 内置），不同接口之间互不阻塞；
 *       批量补报按接口分片、分小块持锁，不长时间卡住同接口的实时写入；</li>
 *   <li>内存有界：时间维度由 {@code retentionMillis} 淘汰过老整桶，
 *       数量维度由每接口 {@code maxEvents} 上限按"时间戳最旧先淘汰"规则逐条淘汰，
 *       因容量被淘汰的量按接口单独计数暴露。</li>
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
    /** 批量补报时单次持锁处理的最大事件数，避免长时间阻塞同接口实时写入。 */
    private static final int BACKFILL_CHUNK_SIZE = 128;

    private final long windowMillis;
    private final long bucketMillis;
    /** 数据保留时长（>= windowMillis）：决定历史回看能追溯多久、补报能补多老的数据。 */
    private final long retentionMillis;
    /** 全局默认的每接口保留上限（<=0 表示不限）。 */
    private final long defaultMaxEventsPerApi;
    private final Clock clock;
    private final ConcurrentHashMap<String, ApiWindow> apis = new ConcurrentHashMap<>();
    /** 每接口保留上限覆盖值（<=0 表示不限）。 */
    private final ConcurrentHashMap<String, Long> maxEventsOverrides = new ConcurrentHashMap<>();

    public MetricsRegistry() {
        this(DEFAULT_WINDOW_MILLIS, DEFAULT_BUCKET_MILLIS, new SystemClock());
    }

    public MetricsRegistry(long windowMillis, long bucketMillis, Clock clock) {
        this(windowMillis, bucketMillis, windowMillis, 0L, clock);
    }

    /**
     * 完整构造。
     *
     * @param retentionMillis      数据保留时长，必须 >= windowMillis；等于 windowMillis 时
     *                             行为与旧版一致（只能回看当前窗口）
     * @param maxEventsPerApi      每接口默认保留上限，<=0 表示不限
     */
    public MetricsRegistry(long windowMillis, long bucketMillis, long retentionMillis,
                           long maxEventsPerApi, Clock clock) {
        if (windowMillis <= 0) {
            throw new IllegalArgumentException("windowMillis must be positive: " + windowMillis);
        }
        if (bucketMillis <= 0 || bucketMillis > windowMillis) {
            throw new IllegalArgumentException(
                    "bucketMillis must be in (0, windowMillis]: " + bucketMillis);
        }
        if (retentionMillis < windowMillis) {
            throw new IllegalArgumentException(
                    "retentionMillis must be >= windowMillis: " + retentionMillis);
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.windowMillis = windowMillis;
        this.bucketMillis = bucketMillis;
        this.retentionMillis = retentionMillis;
        this.defaultMaxEventsPerApi = maxEventsPerApi;
        this.clock = clock;
    }

    /** 记录一次调用（使用注册表时钟）。 */
    public void record(String api, CallStatus status, long latencyMillis) {
        record(api, status, latencyMillis, clock.nowMillis());
    }

    /** 记录一次调用（显式指定事件时间戳，主要用于测试窗口滚动）。 */
    public void record(String api, CallStatus status, long latencyMillis, long timestampMillis) {
        validateFields(api, status, latencyMillis);
        long now = clock.nowMillis();
        checkNotFuture(api, timestampMillis, now);
        if (timestampMillis < now - windowMillis) {
            throw new InvalidMetricException(REASON_TIMESTAMP_TOO_OLD,
                    "timestampMillis " + timestampMillis + " is older than the window start "
                            + (now - windowMillis) + " for api=" + api);
        }
        ApiWindow window = apis.computeIfAbsent(api, ignored -> new ApiWindow());
        window.addRealtime(api, status, latencyMillis, timestampMillis, now);
    }

    /** 记录一个事件对象。 */
    public void record(CallEvent event) {
        if (event == null) {
            throw new InvalidMetricException(REASON_NULL_EVENT, "call event must not be null");
        }
        record(event.api(), event.status(), event.latencyMillis(), event.timestampMillis());
    }

    /**
     * 批量历史补报：接受时间戳早于已见数据、批内乱序的事件，回填到其本来所属的时间桶。
     *
     * <p>口径保证：补报事件与当时实时写入走完全相同的归桶与聚合逻辑，补报完成后同一
     * 时间窗口的聚合结果与实时写入一致。时间戳超过保留期（{@code now - retentionMillis}）
     * 或落在未来的事件逐条拒绝并计入回执，不静默丢弃。
     *
     * <p>并发：按接口分组、每组分小块持锁处理，块与块之间释放锁，实时写入可正常穿插。
     */
    public BackfillReceipt backfill(Collection<CallEvent> events) {
        if (events == null) {
            throw new InvalidMetricException(REASON_NULL_EVENT, "backfill events must not be null");
        }
        // 按接口分组并保留原始下标，便于回执定位
        Map<String, List<IndexedEvent>> byApi = new LinkedHashMap<>();
        List<BackfillReceipt.Rejection> rejections = new ArrayList<>();
        int index = 0;
        for (CallEvent event : events) {
            if (event == null) {
                rejections.add(new BackfillReceipt.Rejection(
                        index, null, REASON_NULL_EVENT, "call event must not be null"));
            } else if (event.api() == null || event.api().isBlank()) {
                rejections.add(new BackfillReceipt.Rejection(
                        index, event.api(), REASON_BLANK_API, "api must not be blank"));
            } else {
                byApi.computeIfAbsent(event.api(), ignored -> new ArrayList<>())
                        .add(new IndexedEvent(index, event));
            }
            index++;
        }

        Map<String, Map<Long, Integer>> acceptedByBucket = new LinkedHashMap<>();
        for (Map.Entry<String, List<IndexedEvent>> entry : byApi.entrySet()) {
            String api = entry.getKey();
            List<IndexedEvent> group = entry.getValue();
            ApiWindow window = apis.computeIfAbsent(api, ignored -> new ApiWindow());
            Map<Long, Integer> bucketHits = new TreeMap<>();
            // 分小块持锁：每块最多 BACKFILL_CHUNK_SIZE 条，块间释放锁让实时写入穿插
            for (int from = 0; from < group.size(); from += BACKFILL_CHUNK_SIZE) {
                List<IndexedEvent> chunk =
                        group.subList(from, Math.min(from + BACKFILL_CHUNK_SIZE, group.size()));
                synchronized (window) {
                    long now = clock.nowMillis();
                    window.evictExpired(now);
                    for (IndexedEvent ie : chunk) {
                        CallEvent e = ie.event();
                        try {
                            window.addBackfill(api, e.status(), e.latencyMillis(),
                                    e.timestampMillis(), now, retentionMillis);
                            bucketHits.merge(window.floorToBucket(e.timestampMillis()), 1,
                                    Integer::sum);
                        } catch (InvalidMetricException ex) {
                            rejections.add(new BackfillReceipt.Rejection(
                                    ie.index(), api, ex.reason(), ex.getMessage()));
                        }
                    }
                    window.enforceCapacity(api, effectiveMaxEvents(api));
                }
            }
            acceptedByBucket.put(api, bucketHits);
        }
        // 接受数 = 各桶落桶计数之和，与回执的落桶明细自洽
        int accepted = 0;
        for (Map<Long, Integer> hits : acceptedByBucket.values()) {
            for (int n : hits.values()) {
                accepted += n;
            }
        }
        // 拒绝明细按下标排序，回执对同一输入的呈现稳定、可预期
        rejections.sort(Comparator.comparingInt(BackfillReceipt.Rejection::index));
        return new BackfillReceipt(accepted, rejections.size(),
                List.copyOf(rejections), acceptedByBucket);
    }

    /** 取某接口当前窗口聚合；该接口在窗口内无任何数据时返回 empty。 */
    public Optional<WindowSnapshot> snapshot(String api) {
        checkApi(api);
        ApiWindow window = apis.get(api);
        if (window == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(window.aggregate(api, clock.nowMillis(), windowMillis));
    }

    /**
     * 按历史时刻回看：返回"若在 {@code timestampMillis} 那一刻取快照"会得到的窗口聚合，
     * 窗口为 {@code [t-window, t]}，只统计事件时间戳 <= t 的数据，不受之后写入的影响。
     *
     * <p>结果稳定性：同一 t 反复查询结果一致；但数据仍受保留期与容量上限约束，
     * 已被淘汰的历史数据无法还原（回看越老的时刻，越可能只反映仍保留的部分）。
     */
    public Optional<WindowSnapshot> snapshotAt(String api, long timestampMillis) {
        checkApi(api);
        long now = clock.nowMillis();
        checkNotFuture(api, timestampMillis, now);
        ApiWindow window = apis.get(api);
        if (window == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(window.aggregateAt(api, timestampMillis, now, windowMillis));
    }

    /**
     * 设置单接口保留上限（事件条数）。{@code maxEvents <= 0} 表示不限。
     * 设置后立即按"时间戳最旧先淘汰"规则收敛到上限以内。
     */
    public void setMaxEvents(String api, long maxEvents) {
        checkApi(api);
        maxEventsOverrides.put(api, maxEvents);
        ApiWindow window = apis.get(api);
        if (window != null) {
            synchronized (window) {
                window.enforceCapacity(api, effectiveMaxEvents(api));
            }
        }
    }

    /** 该接口因容量上限被淘汰的事件累计数（单调不减，永不出现负值）。 */
    public long evictedCount(String api) {
        checkApi(api);
        ApiWindow window = apis.get(api);
        if (window == null) {
            return 0L;
        }
        synchronized (window) {
            return window.evictedByCapacity;
        }
    }

    /** 该接口当前仍保留的事件数。 */
    public long retainedCount(String api) {
        checkApi(api);
        ApiWindow window = apis.get(api);
        if (window == null) {
            return 0L;
        }
        synchronized (window) {
            return window.totalEvents;
        }
    }

    public long windowMillis() {
        return windowMillis;
    }

    public long bucketMillis() {
        return bucketMillis;
    }

    public long retentionMillis() {
        return retentionMillis;
    }

    private long effectiveMaxEvents(String api) {
        Long override = maxEventsOverrides.get(api);
        return override != null ? override : defaultMaxEventsPerApi;
    }

    private void checkApi(String api) {
        if (api == null || api.isBlank()) {
            throw new InvalidMetricException(REASON_BLANK_API, "api must not be blank");
        }
    }

    private void validateFields(String api, CallStatus status, long latencyMillis) {
        checkApi(api);
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
    }

    private void checkNotFuture(String api, long timestampMillis, long now) {
        if (timestampMillis > now) {
            throw new InvalidMetricException(REASON_TIMESTAMP_IN_FUTURE,
                    "timestampMillis " + timestampMillis + " is in the future (now=" + now
                            + ") for api=" + api);
        }
    }

    /** 单接口的滚动窗口。所有字段仅在持有实例锁时访问。 */
    private final class ApiWindow {
        /** 桶起始毫秒 -> 桶。TreeMap 支持乱序回填任意保留中的桶。 */
        private final TreeMap<Long, Bucket> buckets = new TreeMap<>();
        private long totalEvents;
        private long evictedByCapacity;

        /** 实时写入：严格模式，乱序到更早桶的事件明确拒绝（保持既有口径）。 */
        synchronized void addRealtime(String api, CallStatus status, long latency, long timestamp,
                                      long now) {
            long bucketStart = floorToBucket(timestamp);
            evictExpired(now);
            if (bucketStart + bucketMillis <= now - windowMillis) {
                throw new InvalidMetricException(REASON_TIMESTAMP_TOO_OLD,
                        "timestampMillis " + timestamp
                                + " already rolled out of the window for api=" + api);
            }
            Map.Entry<Long, Bucket> last = buckets.lastEntry();
            if (last != null && bucketStart < last.getKey()) {
                throw new InvalidMetricException(REASON_TIMESTAMP_TOO_OLD,
                        "out-of-order timestampMillis " + timestamp
                                + " cannot be backfilled into an earlier bucket for api=" + api
                                + "; use backfill() for historical events");
            }
            insert(bucketStart, status, latency, timestamp);
            enforceCapacity(api, effectiveMaxEvents(api));
        }

        /** 补报写入：允许乱序与早于已见数据的时间戳，仅受保留期约束。调用方须持锁。 */
        void addBackfill(String api, CallStatus status, long latency, long timestamp, long now,
                         long retention) {
            validateFields(api, status, latency);
            checkNotFuture(api, timestamp, now);
            if (timestamp < now - retention) {
                throw new InvalidMetricException(REASON_TIMESTAMP_TOO_OLD,
                        "timestampMillis " + timestamp + " is older than the retention start "
                                + (now - retention) + " for api=" + api);
            }
            insert(floorToBucket(timestamp), status, latency, timestamp);
        }

        /** 归桶并落一条事件，调用方须持锁。 */
        private void insert(long bucketStart, CallStatus status, long latency, long timestamp) {
            Bucket bucket = buckets.computeIfAbsent(bucketStart, Bucket::new);
            bucket.insert(new StoredEvent(timestamp, status, latency));
            totalEvents++;
        }

        /**
         * 容量淘汰：超过上限时按"桶从早到晚、桶内按事件时间戳从旧到新"逐条淘汰。
         * 规则固定可预期；只删事件，计数由现存事件现算，不会出现负值。调用方须持锁。
         */
        void enforceCapacity(String api, long maxEvents) {
            if (maxEvents <= 0) {
                return;
            }
            while (totalEvents > maxEvents) {
                Map.Entry<Long, Bucket> first = buckets.firstEntry();
                if (first == null) {
                    // 防御：计数与桶不一致时归零纠正，绝不出现负值
                    totalEvents = 0;
                    return;
                }
                Bucket bucket = first.getValue();
                bucket.removeOldest();
                totalEvents--;
                evictedByCapacity++;
                if (bucket.isEmpty()) {
                    buckets.pollFirstEntry();
                }
            }
        }

        /** 当前时刻的窗口聚合（实时路径）。 */
        synchronized WindowSnapshot aggregate(String api, long now, long window) {
            evictExpired(now);
            return doAggregate(api, now - window, now);
        }

        /** 历史时刻回看：先按当前时间做保留期淘汰，再以 t 为窗口右界聚合。 */
        synchronized WindowSnapshot aggregateAt(String api, long t, long now, long window) {
            evictExpired(now);
            return doAggregate(api, t - window, t);
        }

        /** 聚合 [fromInclusive, toInclusive] 内的事件，调用方须持锁。 */
        private WindowSnapshot doAggregate(String api, long fromInclusive, long toInclusive) {
            long total = 0L;
            long success = 0L;
            long failure = 0L;
            long latencySum = 0L;
            long maxLatency = 0L;
            List<Long> latencies = new ArrayList<>();
            // 只需扫描与 [from, to] 可能相交的桶：start <= to 且 start+bucket > from
            for (Bucket b : buckets.tailMap(floorToBucket(fromInclusive), true).values()) {
                if (b.startMillis > toInclusive) {
                    break;
                }
                for (StoredEvent e : b.events) {
                    if (e.timestampMillis() < fromInclusive || e.timestampMillis() > toInclusive) {
                        continue;
                    }
                    total++;
                    if (e.status() == CallStatus.SUCCESS) {
                        success++;
                    } else {
                        failure++;
                    }
                    latencySum += e.latencyMillis();
                    if (e.latencyMillis() > maxLatency) {
                        maxLatency = e.latencyMillis();
                    }
                    latencies.add(e.latencyMillis());
                }
            }
            if (total == 0L) {
                return null;
            }
            latencies.sort(Long::compareTo);
            double successRate = (double) success / total;
            double failureRate = (double) failure / total;
            double avgLatency = (double) latencySum / total;
            return new WindowSnapshot(api, fromInclusive, toInclusive, total, success, failure,
                    successRate, failureRate, avgLatency, maxLatency, latencies);
        }

        /**
         * 淘汰与保留区间 {@code [now-retention, now]} 不再相交的整桶，调用方须持锁。
         */
        private void evictExpired(long now) {
            long retentionLeft = now - retentionMillis;
            while (!buckets.isEmpty()
                    && buckets.firstKey() + bucketMillis <= retentionLeft) {
                Bucket removed = buckets.pollFirstEntry().getValue();
                totalEvents -= removed.events.size();
            }
        }

        private long floorToBucket(long millis) {
            return Math.floorDiv(millis, bucketMillis) * bucketMillis;
        }
    }

    /** 定宽时间桶：逐事件存储，按事件时间戳升序。 */
    private static final class Bucket {
        private final long startMillis;
        private final List<StoredEvent> events = new ArrayList<>();

        private Bucket(long startMillis) {
            this.startMillis = startMillis;
        }

        /** 按时间戳有序插入（二分定位），保证"最旧先淘汰"与回看过滤的正确性。 */
        private void insert(StoredEvent event) {
            int lo = 0;
            int hi = events.size();
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (events.get(mid).timestampMillis() <= event.timestampMillis()) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            events.add(lo, event);
        }

        private void removeOldest() {
            events.remove(0);
        }

        private boolean isEmpty() {
            return events.isEmpty();
        }
    }

    /** 一条已落桶的调用事件。 */
    private record StoredEvent(long timestampMillis, CallStatus status, long latencyMillis) {
    }

    /** 带原始下标的补报事件，用于回执定位。 */
    private record IndexedEvent(int index, CallEvent event) {
    }
}
