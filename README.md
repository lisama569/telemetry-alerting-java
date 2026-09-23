# Telemetry Alerting（进程内接口调用指标采集与告警判定）

纯进程内实现，**不依赖任何外部服务、存储或真实凭据**。支持按接口采集调用次数、
成功/失败与延迟分布，基于滚动时间窗口聚合，并对窗口指标执行带抖动确认与稳定恢复的
告警状态机评估。时间源（`TimeSource`）可注入，测试中使用可控时钟，结果可预期、可复现。

## 模块结构

| 包 | 类型 | 职责 |
| --- | --- | --- |
| `common` | `TimeSource` / `SystemTimeSource` | 时间抽象，测试可替换为 `MutableTimeSource` |
| `model` | `CallRecordPoint` / `MetricSnapshot` / `CallStatus` / `MetricType` | 埋点、聚合快照与指标枚举 |
| `exception` | `TelemetryException` 及 3 个子类 | 携带**可区分原因码**的拒绝异常 |
| `collector` | `MetricsCollector` / `InMemoryMetricsCollector` | 并发安全的滚动窗口采集 |
| `alerting` | `AlertRule` / `AlertState` / `AlertStateEntity` / `DefaultAlertEvaluator` | 规则校验、状态机、幂等评估 |
| `service` | `TelemetryService` / `DefaultTelemetryService` | 组合采集器与评估器的门面 |
| `config` | `TelemetryAutoConfiguration` | Spring Boot Bean 装配 |

## 指标口径

### 滚动窗口

- 窗口为 **左闭右闭** 区间 `[now - windowMillis, now]`，单位毫秒；`windowMillis` 必须为正数。
- 埋点按接口分别存储在按时间戳升序的列表中；每次查询时先淘汰时间戳早于窗口起点的整条记录。
- 所有计数都由窗口内的埋点实时汇总得到，过期记录只做整条删除，
  **不存在扣减操作，因此任何指标都不可能为负**。
- 窗口内无数据时返回零值快照（计数 0、比率 0、延迟 0）。

### 聚合字段（`MetricSnapshot`）

| 字段 | 口径 |
| --- | --- |
| `totalCount` | 窗口内调用总数，恒等于 `successCount + failureCount` |
| `successCount` / `failureCount` | 按 `CallStatus` 计数 |
| `successRate` / `failureRate` | 成功/失败计数 ÷ 总数，四舍五入保留 4 位小数；无数据为 0 |
| `avgLatencyMillis` | 窗口内全部延迟的算术平均，保留 4 位小数 |
| `min/maxLatencyMillis` | 窗口内最小/最大延迟 |
| `p50/p95/p99LatencyMillis` | **最近秩（nearest-rank）百分位**：`rank = ceil(p/100 * n)`，取升序第 rank 个 |

可被规则引用的指标见 `MetricType`：计数类（TOTAL/SUCCESS/FAILURE_COUNT）、
比率类（SUCCESS/FAILURE_RATE）、延迟类（AVG/P50/P95/P99/MAX_LATENCY_MILLIS）。

### 埋点合法性（拒绝即不写入，原因码 `INVALID_RECORD_*`）

| 规则 | 原因码 |
| --- | --- |
| 埋点为 null | `INVALID_RECORD_NULL` |
| `endpoint` 为 null/空白 | `INVALID_RECORD_ENDPOINT_BLANK` |
| `timestampMillis <= 0` | `INVALID_RECORD_TIMESTAMP_MISSING` |
| 时间戳晚于当前时间（5ms 时钟偏差容忍） | `INVALID_RECORD_TIMESTAMP_FUTURE` |
| `latencyMillis < 0` | `INVALID_RECORD_LATENCY_NEGATIVE` |
| 延迟超过 `now - timestamp`（+5ms 容忍） | `INVALID_RECORD_LATENCY_OUT_OF_RANGE` |
| `status` 为 null | `INVALID_RECORD_STATUS_MISSING` |

校验在持锁前后各执行一次，非法埋点**不会产生任何统计写入**。

## 告警状态迁移规则

一次 `evaluate` 视为一个评估节拍，规则字段：指标、算子、触发阈值、
`pendingStreak`（连续命中确认次数，≥1）、`recoverStreak`（连续健康恢复次数，≥1）。

```
                 命中且连续达到 pendingStreak 次
 NOT_TRIGGERED ───────────────────────────────► FIRING（pendingStreak=1 时直达）
 NOT_TRIGGERED ──命中(未达次数)──► PENDING
 PENDING        ──命中(未达次数)──► PENDING（保持）
 PENDING        ──健康──► NOT_TRIGGERED（待确认期间抖动不触发）
 FIRING         ──健康次数 < recoverStreak──► FIRING（单次抖动不解除告警，命中即清零健康计数）
 FIRING         ──连续健康达到 recoverStreak 次──► RESOLVED
 RESOLVED       ──再次命中──► PENDING（恢复后须重新确认，不会直接回到 FIRING）
 RESOLVED       ──健康──► RESOLVED（保持）
```

要点：

1. **短暂抖动不触发**：首次命中只进入 `PENDING`，必须连续命中达到 `pendingStreak` 才 `FIRING`。
2. **短暂抖动不解除**：`FIRING` 后单次健康保持 `FIRING`，且其间再命中会把健康连续次数清零，
   必须连续健康达到 `recoverStreak` 才 `RESOLVED`。
3. **幂等评估**：对同一规则，在同一时间桶（10ms）内以相同窗口与指标值重复评估时，
   直接回放上次结果（`idempotentReplay=true`），状态与 streak 均不改变。
4. 每次评估返回 `EvaluationResult`，其中 `reason` 用中文给出判定依据。

### 规则合法性（原因码 `INVALID_RULE_*`）

| 规则 | 原因码 |
| --- | --- |
| 规则为 null / id 空白 / endpoint 空白 | `INVALID_RULE_NULL` / `INVALID_RULE_ID_BLANK` / `INVALID_RULE_ENDPOINT_BLANK` |
| 指标为 null（未知指标） | `INVALID_RULE_METRIC_UNKNOWN` |
| 算子为 null | `INVALID_RULE_OPERATOR_MISSING` |
| 阈值为 NaN/Infinity | `INVALID_RULE_THRESHOLD_ILLEGAL` |
| 比率指标阈值不在 [0,1] | `INVALID_RULE_THRESHOLD_OUT_OF_RANGE` |
| 计数/延迟指标阈值为负 | `INVALID_RULE_THRESHOLD_NEGATIVE` |
| `pendingStreak < 1` / `recoverStreak < 1` | `INVALID_RULE_PENDING_STREAK` / `INVALID_RULE_RECOVER_STREAK` |
| 重复注册同一 ruleId | `INVALID_RULE_DUPLICATE_ID` |

评估期异常（原因码 `EVAL_*`）：`EVAL_RULE_UNKNOWN`（未注册规则）、
`EVAL_METRIC_MISSING`（快照为 null）、`EVAL_METRIC_ENDPOINT_MISMATCH`（快照接口与规则不匹配）。
非法输入一律抛异常，绝不静默计入或默认放行。

## 并发正确性

- 每个接口一个 `EndpointData`（独立锁 + 有序埋点列表），不同接口完全并行。
- 写入路径：先校验后追加，且只在该接口锁内追加一次，故无丢失、无重复；
  16 线程 × 1000 次的压力断言 `total == 真实请求数` 且成功/失败拆分精确。
- 评估器按规则加锁，状态与 streak 的读改写原子完成；`getState` 返回防御性拷贝。

## 使用方式

```java
TimeSource clock = new SystemTimeSource();
MetricsCollector collector = new InMemoryMetricsCollector(clock);
AlertEvaluator evaluator = new DefaultAlertEvaluator(clock);
TelemetryService telemetry = new DefaultTelemetryService(collector, evaluator);

telemetry.record(new CallRecordPoint("/api/order", clock.nowMillis() - 10, 12L, CallStatus.SUCCESS));

telemetry.registerRule(new AlertRule("rule-1", "/api/order",
        MetricType.FAILURE_RATE, ComparisonOperator.GREATER_THAN, 0.5d,
        3, 2)); // 连续 3 次失败率>0.5 触发，连续 2 次健康恢复

MetricSnapshot s = telemetry.snapshot("/api/order", 60_000L);
EvaluationResult r = telemetry.evaluate("rule-1", 60_000L);
```

Spring Boot 应用中 `TelemetryAutoConfiguration` 已装配全部 Bean；
测试可用一个返回 `MutableTimeSource` 的 `@TestConfiguration` + `@Primary` 覆盖时钟。

## 验证方法

```bash
mvn test            # 全量单测（离线环境可加 -o）
mvn test -Dtest=ConcurrentRecordingTest       # 并发写入计数一致性
mvn test -Dtest=WindowRollingTest             # 窗口滚动/过期淘汰/非负
mvn test -Dtest=AlertStateMachineTest         # 抖动、确认、触发、恢复、幂等
mvn test -Dtest=InvalidRecordTest             # 非法埋点拒绝且不计入
mvn test -Dtest=InvalidRuleTest               # 非法规则与评估异常拒绝
mvn test -Dtest=TelemetryServiceIntegrationTest # Spring 端到端
```

测试日志（SLF4J）逐条打印**输入参数、聚合结果与状态判定依据**，例如：

```
[状态机] tick@7050005: 输入指标 failureRate=1.0 total=10 => 状态=FIRING, breach=true,
  breachStreak=3, healthyStreak=0, changed=false, replay=false | 依据: 连续命中 3 次达到
  待确认次数 3，待确认→触发中
```

全部测试均基于注入的可控时钟推进，不依赖真实时间，因此结果稳定、可重复。
