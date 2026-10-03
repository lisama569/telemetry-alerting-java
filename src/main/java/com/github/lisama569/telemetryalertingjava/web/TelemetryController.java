package com.github.lisama569.telemetryalertingjava.web;

import com.github.lisama569.telemetryalertingjava.alert.AlertEvaluation;
import com.github.lisama569.telemetryalertingjava.alert.AlertEvaluator;
import com.github.lisama569.telemetryalertingjava.alert.AlertRule;
import com.github.lisama569.telemetryalertingjava.alert.ComparisonOperator;
import com.github.lisama569.telemetryalertingjava.alert.InvalidAlertRuleException;
import com.github.lisama569.telemetryalertingjava.metrics.BackfillReport;
import com.github.lisama569.telemetryalertingjava.metrics.CallEvent;
import com.github.lisama569.telemetryalertingjava.metrics.CallStatus;
import com.github.lisama569.telemetryalertingjava.metrics.EvictionStats;
import com.github.lisama569.telemetryalertingjava.metrics.InvalidMetricException;
import com.github.lisama569.telemetryalertingjava.metrics.MetricType;
import com.github.lisama569.telemetryalertingjava.metrics.MetricsRegistry;
import com.github.lisama569.telemetryalertingjava.metrics.WindowSnapshot;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 进程内指标采集与告警评估的 HTTP 入口（薄封装，核心逻辑在 metrics/alert 包，无外部依赖）。
 */
@RestController
@RequestMapping("/telemetry")
public class TelemetryController {

    private final MetricsRegistry registry;
    private final AlertEvaluator evaluator;

    public TelemetryController(MetricsRegistry registry, AlertEvaluator evaluator) {
        this.registry = registry;
        this.evaluator = evaluator;
    }

    /** 上报一次调用埋点。 */
    @PostMapping("/events")
    public Map<String, Object> record(@RequestBody RecordRequest request) {
        CallStatus status = parseStatus(request.status());
        Long latency = request.latencyMillis();
        // 缺失字段在此处即被拒绝，原因码与核心层保持一致，不静默放行
        if (latency == null) {
            throw new InvalidMetricException(MetricsRegistry.REASON_NEGATIVE_LATENCY,
                    "latencyMillis is required");
        }
        registry.record(request.api(), status, latency);
        return Map.of("accepted", true, "api", String.valueOf(request.api()));
    }

    /** 查询接口当前窗口聚合。 */
    @GetMapping("/metrics/{api}")
    public ResponseEntity<WindowSnapshot> snapshot(@PathVariable String api) {
        return registry.snapshot(api)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    /**
     * 历史补报：批量写入乱序、时间戳早于已见数据的埋点。
     * 单条非法不拖垮整批，全部结果（接受落点 + 拒绝明细）体现在回执里。
     */
    @PostMapping("/events/backfill")
    public BackfillReport backfill(@RequestBody BackfillRequest request) {
        List<BackfillRequest.BackfillEvent> raw = request == null ? null : request.events();
        if (raw == null) {
            throw new InvalidMetricException(MetricsRegistry.REASON_NULL_EVENT,
                    "events must not be null");
        }
        // 逐条解析：非法条目转为回执拒绝项（保留原始下标），合法条目交给核心层批量写入
        List<CallEvent> parsed = new ArrayList<>();
        List<Integer> parsedIndex = new ArrayList<>();
        List<BackfillReport.Rejection> parseRejections = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            BackfillRequest.BackfillEvent e = raw.get(i);
            if (e == null) {
                parseRejections.add(new BackfillReport.Rejection(
                        i, null, MetricsRegistry.REASON_NULL_EVENT, "call event must not be null"));
                continue;
            }
            try {
                if (e.timestampMillis() == null) {
                    throw new InvalidMetricException(MetricsRegistry.REASON_NULL_TIMESTAMP,
                            "timestampMillis is required for backfill events");
                }
                if (e.latencyMillis() == null) {
                    throw new InvalidMetricException(MetricsRegistry.REASON_NEGATIVE_LATENCY,
                            "latencyMillis is required");
                }
                parsed.add(CallEvent.of(e.api(), parseStatus(e.status()),
                        e.latencyMillis(), e.timestampMillis()));
                parsedIndex.add(i);
            } catch (InvalidMetricException ex) {
                parseRejections.add(new BackfillReport.Rejection(
                        i, e.api(), ex.reason(), ex.getMessage()));
            }
        }
        BackfillReport core = registry.backfill(parsed);
        // 核心层回执的下标是解析后列表的下标，映射回原始批次下标后与解析期拒绝项合并
        List<BackfillReport.Rejection> rejections = new ArrayList<>(parseRejections);
        for (BackfillReport.Rejection r : core.rejections()) {
            rejections.add(new BackfillReport.Rejection(
                    parsedIndex.get(r.index()), r.api(), r.reason(), r.message()));
        }
        rejections.sort(Comparator.comparingInt(BackfillReport.Rejection::index));
        return new BackfillReport(raw.size(), core.accepted(), core.slots(), rejections);
    }

    /** 按时间点回看：查询历史时刻所在窗口的聚合，结果不受之后新写入影响。 */
    @GetMapping("/metrics/{api}/at")
    public ResponseEntity<WindowSnapshot> snapshotAt(@PathVariable String api,
                                                     @RequestParam long timestampMillis) {
        return registry.snapshotAt(api, timestampMillis)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    /** 查询接口的容量淘汰统计（因容量上限被淘汰的量，单独暴露）。 */
    @GetMapping("/metrics/{api}/evictions")
    public EvictionStats evictions(@PathVariable String api) {
        return registry.evictionStats(api);
    }

    /** 注册告警规则。 */
    @PostMapping("/rules")
    public Map<String, Object> registerRule(@RequestBody RegisterRuleRequest request) {
        AlertRule rule = AlertRule.of(
                request.ruleId(),
                request.api(),
                parseEnum(MetricType.class, request.metricType(), "metricType"),
                request.percentile() == null ? 0d : request.percentile(),
                parseEnum(ComparisonOperator.class, request.operator(), "operator"),
                request.threshold() == null ? Double.NaN : request.threshold(),
                request.pendingRequired() == null ? 1 : request.pendingRequired(),
                request.recoveryRequired() == null ? 1 : request.recoveryRequired());
        evaluator.register(rule);
        return Map.of("registered", true, "ruleId", rule.ruleId());
    }

    /** 评估一次规则。 */
    @PostMapping("/rules/{ruleId}/evaluate")
    public AlertEvaluation evaluate(@PathVariable String ruleId) {
        return evaluator.evaluate(ruleId);
    }

    private CallStatus parseStatus(String raw) {
        if (raw == null) {
            throw new InvalidMetricException(MetricsRegistry.REASON_NULL_STATUS,
                    "status is required (SUCCESS|FAILURE)");
        }
        try {
            return CallStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new InvalidMetricException(MetricsRegistry.REASON_NULL_STATUS,
                    "unknown call status: " + raw);
        }
    }

    private <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String field) {
        if (raw == null) {
            throw new InvalidAlertRuleException("NULL_" + field.toUpperCase(),
                    field + " is required");
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new InvalidAlertRuleException("UNKNOWN_" + field.toUpperCase(),
                    "unknown " + field + ": " + raw);
        }
    }

    @ExceptionHandler(InvalidMetricException.class)
    public ResponseEntity<ErrorResponse> handleMetric(InvalidMetricException ex) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("invalid_metric", ex.reason(), ex.getMessage()));
    }

    @ExceptionHandler(InvalidAlertRuleException.class)
    public ResponseEntity<ErrorResponse> handleRule(InvalidAlertRuleException ex) {
        HttpStatus status = AlertEvaluator.REASON_UNKNOWN_RULE.equals(ex.reason())
                ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status)
                .body(new ErrorResponse("invalid_alert_rule", ex.reason(), ex.getMessage()));
    }
}
