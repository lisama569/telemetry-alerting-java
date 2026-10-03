package com.github.lisama569.telemetryalertingjava.web;

import com.github.lisama569.telemetryalertingjava.alert.AlertEvaluation;
import com.github.lisama569.telemetryalertingjava.alert.AlertEvaluator;
import com.github.lisama569.telemetryalertingjava.alert.AlertRule;
import com.github.lisama569.telemetryalertingjava.alert.ComparisonOperator;
import com.github.lisama569.telemetryalertingjava.alert.InvalidAlertRuleException;
import com.github.lisama569.telemetryalertingjava.metrics.BackfillReceipt;
import com.github.lisama569.telemetryalertingjava.metrics.CallEvent;
import com.github.lisama569.telemetryalertingjava.metrics.CallStatus;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
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

    /** 查询接口当前窗口聚合；带 at 参数时按历史时刻回看。 */
    @GetMapping("/metrics/{api}")
    public ResponseEntity<WindowSnapshot> snapshot(
            @PathVariable String api,
            @org.springframework.web.bind.annotation.RequestParam(required = false) Long at) {
        if (at != null) {
            return registry.snapshotAt(api, at)
                    .map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
        }
        return registry.snapshot(api)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    /** 批量历史补报：乱序/早于已见数据的事件回填到所属时间桶，返回回执。 */
    @PostMapping("/events/backfill")
    public BackfillReceipt backfill(@RequestBody BackfillRequest request) {
        if (request == null || request.events() == null) {
            throw new InvalidMetricException(MetricsRegistry.REASON_NULL_EVENT,
                    "events is required");
        }
        List<CallEvent> events = new ArrayList<>(request.events().size());
        for (BackfillRequest.Item item : request.events()) {
            if (item == null) {
                events.add(null);
                continue;
            }
            // 缺字段的事件构造为非法事件，由核心层逐条拒绝并计入回执，不静默丢弃
            CallStatus status = item.status() == null ? null : parseStatus(item.status());
            events.add(new CallEvent(item.api(), status,
                    item.latencyMillis() == null ? -1L : item.latencyMillis(),
                    item.timestampMillis() == null ? Long.MAX_VALUE : item.timestampMillis()));
        }
        return registry.backfill(events);
    }

    /** 设置单接口保留上限（事件条数，<=0 表示不限）。 */
    @PutMapping("/metrics/{api}/capacity")
    public Map<String, Object> setCapacity(@PathVariable String api,
                                           @RequestBody CapacityRequest request) {
        long maxEvents = request == null || request.maxEvents() == null ? 0L : request.maxEvents();
        registry.setMaxEvents(api, maxEvents);
        return capacityView(api);
    }

    /** 查询接口容量状况：上限、当前保留数、因容量被淘汰的累计数。 */
    @GetMapping("/metrics/{api}/capacity")
    public Map<String, Object> capacity(@PathVariable String api) {
        return capacityView(api);
    }

    private Map<String, Object> capacityView(String api) {
        return Map.of(
                "api", api,
                "retainedEvents", registry.retainedCount(api),
                "evictedByCapacity", registry.evictedCount(api));
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
