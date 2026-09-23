package com.github.lisama569.telemetryalertingjava.service;

import com.github.lisama569.telemetryalertingjava.alerting.AlertRule;
import com.github.lisama569.telemetryalertingjava.alerting.EvaluationResult;
import com.github.lisama569.telemetryalertingjava.collector.MetricsCollector;
import com.github.lisama569.telemetryalertingjava.model.CallRecordPoint;
import com.github.lisama569.telemetryalertingjava.model.MetricSnapshot;

/** 组合采集器与评估器的门面（骨架）。 */
public interface TelemetryService {

    void record(CallRecordPoint point);

    MetricSnapshot snapshot(String endpoint, long windowMillis);

    void registerRule(AlertRule rule);

    EvaluationResult evaluate(String ruleId, long windowMillis);

    MetricsCollector collector();
}
