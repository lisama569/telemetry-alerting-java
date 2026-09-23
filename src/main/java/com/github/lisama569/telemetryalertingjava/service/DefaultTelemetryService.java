package com.github.lisama569.telemetryalertingjava.service;

import com.github.lisama569.telemetryalertingjava.alerting.AlertEvaluator;
import com.github.lisama569.telemetryalertingjava.alerting.AlertRule;
import com.github.lisama569.telemetryalertingjava.alerting.EvaluationResult;
import com.github.lisama569.telemetryalertingjava.collector.MetricsCollector;
import com.github.lisama569.telemetryalertingjava.model.CallRecordPoint;
import com.github.lisama569.telemetryalertingjava.model.MetricSnapshot;

/** 组合采集器与评估器的门面默认实现。 */
public class DefaultTelemetryService implements TelemetryService {

    private final MetricsCollector collector;
    private final AlertEvaluator evaluator;

    public DefaultTelemetryService(MetricsCollector collector, AlertEvaluator evaluator) {
        if (collector == null) {
            throw new IllegalArgumentException("MetricsCollector 不允许为 null");
        }
        if (evaluator == null) {
            throw new IllegalArgumentException("AlertEvaluator 不允许为 null");
        }
        this.collector = collector;
        this.evaluator = evaluator;
    }

    @Override
    public void record(CallRecordPoint point) {
        collector.record(point);
    }

    @Override
    public MetricSnapshot snapshot(String endpoint, long windowMillis) {
        return collector.getSnapshot(endpoint, windowMillis);
    }

    @Override
    public void registerRule(AlertRule rule) {
        evaluator.registerRule(rule);
    }

    @Override
    public EvaluationResult evaluate(String ruleId, long windowMillis) {
        AlertRule rule = evaluator.getRule(ruleId);
        MetricSnapshot metricSnapshot = collector.getSnapshot(rule.endpoint(), windowMillis);
        return evaluator.evaluate(ruleId, metricSnapshot);
    }

    @Override
    public MetricsCollector collector() {
        return collector;
    }
}
