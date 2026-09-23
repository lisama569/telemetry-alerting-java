# telemetry-alerting-java（bootstrap-a）

进程内的接口调用指标采集与告警判定组件，基于 Spring Boot 3 / Java 21，**不依赖任何外部服务、数据库或真实凭据**。
全部状态保存在内存中，并发写入与评估结果确定、可复现（测试使用可手动推进的 `FixedClock` 驱动时间）。

## 1. 模块结构

```
core/    Clock（SystemClock / FixedClock）：时间抽象，测试可精确控制窗口滚动
metrics/ CallStatus、MetricType、CallEvent、WindowSnapshot、MetricsRegistry、InvalidMetricException
alert/   AlertState、ComparisonOperator、AlertRule、AlertEvaluation、AlertEvaluator、InvalidAlertRuleException
web/      TelemetryConfiguration + TelemetryController（可选的薄 HTTP 入口）
```

- `MetricsRegistry`：滚动时间窗口计数器，线程安全。
- `AlertEvaluator`：基于窗口指标的规则状态机，线程安全、评估幂等。

## 2. 指标口径

### 2.1 滚动窗口与时间桶

- 窗口长度 `windowMillis`（默认 60000ms），桶宽 `bucketMillis`（默认 1000ms），可用
  `telemetry.window-millis` / `telemetry.bucket-millis` 覆盖。
- 窗口为左闭右开 **`[now-window, now)`**。每个桶覆盖 `[bucketStart, bucketStart+bucket)`，
  与窗口不再相交（`桶结束 <= now-window`）的桶在下次写入/聚合时被整体淘汰。
- 桶内计数**只做加法**，窗口滚动时只从队列头部整桶移除；聚合值 = 现存桶求和。
  因此不存在“先加后减”的中间态，**任何时刻读到的计数都不可能为负**。
- 每个接口一把独立锁（`ApiWindow` 内置 synchronized），不同接口互不阻塞；
  “淘汰过期桶 + 写入/读取”在同一把锁内完成，并发写入**不丢失、不重复**。

### 2.2 聚合字段（`WindowSnapshot`）

| 字段 | 口径 |
| --- | --- |
| `callCount` | 窗口内调用总次数 |
| `successCount` / `failureCount` | 成功 / 失败次数，恒满足 `total = success + failure` |
| `successRate` / `failureRate` | 成功 / 失败占比，范围 `[0,1]`；窗口无调用时定义为 `0`（非 NaN） |
| `avgLatencyMillis` | 窗口内全部样本的平均延迟；无样本为 0 |
| `maxLatencyMillis` | 窗口内最大延迟；无样本为 0 |
| `latencyPercentile(p)` | 延迟分位数，**最近秩法**：`rank = ceil(p/100 * n)`，取第 rank 小样本 |

可被告警规则引用的指标：`CALL_COUNT`、`FAILURE_COUNT`、`SUCCESS_RATE`、`FAILURE_RATE`、
`AVG_LATENCY_MS`、`MAX_LATENCY_MS`、`LATENCY_PERCENTILE`（分位 P 由规则字段给出，`(0,100]`）。

窗口内数据全部过期后，`snapshot(api)` 返回空 `Optional`，与“接口从未上报”一致；
调用方（如告警评估）据此区分“无数据”，不会把无数据静默当作达标。

### 2.3 非法埋点处置（拒绝，不静默计入）

`record(...)` 对每条埋点做严格校验，非法输入抛 `InvalidMetricException` 并携带原因码：

| 原因码 | 触发条件 |
| --- | --- |
| `BLANK_API` | 接口名为 null 或空白 |
| `NULL_STATUS` | 调用状态缺失或无法识别为 SUCCESS/FAILURE |
| `NEGATIVE_LATENCY` | 延迟为负 |
| `LATENCY_TOO_LARGE` | 延迟超过 24h 上限（脏数据） |
| `TIMESTAMP_IN_FUTURE` | 事件时间戳晚于当前时钟 |
| `TIMESTAMP_TOO_OLD` | 事件时间戳已在窗口之外，或乱序事件无法回填到更早的桶 |
| `NULL_EVENT` | 事件对象为 null |

被拒绝的埋点不会产生任何计数。

## 3. 告警状态迁移规则

状态四态：`NOT_TRIGGERED（未触发） → PENDING（待确认） → FIRING（触发中） → RECOVERED（已恢复）`。

规则字段：指标口径 `metricType`、比较算子 `GT/GTE/LT/LTE`、阈值 `threshold`、
连续超标确认次数 `pendingRequired ≥ 1`、连续达标恢复次数 `recoveryRequired ≥ 1`。
每次评估只依据**当前窗口聚合值**是否超标（breach）：

```
NOT_TRIGGERED --breach, 连续>=pending--> FIRING   （pendingRequired=1 时首次超标即 FIRING）
NOT_TRIGGERED --breach, 连续<pending---> PENDING   （短暂抖动不立即触发）
NOT_TRIGGERED --达标--------------------> NOT_TRIGGERED
PENDING       --breach, 连续>=pending--> FIRING
PENDING       --达标--------------------> NOT_TRIGGERED（待确认期的抖动直接取消）
FIRING        --达标, 连续>=recovery---> RECOVERED（恢复必须稳定满足条件）
FIRING        --达标, 连续<recovery----> FIRING    （恢复抖动不解警）
FIRING        --breach------------------> FIRING    （恢复连续次数归零）
RECOVERED     --breach, 连续>=pending--> FIRING
RECOVERED     --breach, 连续<pending---> PENDING
RECOVERED     --达标--------------------> RECOVERED
```

要点：

- **抖动不触发**：未触发/已恢复状态下，超标次数未连续达到 `pendingRequired` 不会进入 FIRING。
- **抖动不解警**：FIRING 状态下达标次数未连续达到 `recoveryRequired`，状态保持 FIRING；
  期间再次超标会把恢复连续计数清零。
- **评估幂等**：同一规则在**同一评估时刻**重复评估，直接返回首次评估的同一结果对象，
  状态与“连续超标/连续恢复”计数不发生任何变化（见
  `AlertEvaluatorTest.repeatedEvaluationAtSameTimeIsIdempotent` 与并发评估测试）。
  评估时刻必须单调非递减，回退将以 `NON_MONOTONIC_EVALUATION_TIME` 拒绝。

### 3.1 非法规则 / 无法评估的处置

- 注册阶段（`AlertRule.of` / `POST /telemetry/rules`）校验失败即拒绝，原因码：
  `BLANK_RULE_ID`、`BLANK_API`、`NULL_METRIC_TYPE`、`NULL_OPERATOR`、
  `NAN_OR_INFINITE_THRESHOLD`、`THRESHOLD_OUT_OF_RANGE`（阈值非负；比率阈值须在 `[0,1]`）、
  `INVALID_PENDING_REQUIRED`、`INVALID_RECOVERY_REQUIRED`、`INVALID_PERCENTILE`；
  重复注册同一 ruleId 返回 `DUPLICATE_RULE_ID`。
- 评估阶段：规则不存在 → `UNKNOWN_RULE`（HTTP 404）；
  **规则引用的接口在窗口内没有任何数据 → `UNKNOWN_METRIC`**，不默认当作达标放行；
  评估时间回退 → `NON_MONOTONIC_EVALUATION_TIME`。

每次评估返回的 `AlertEvaluation` 都带 `breached`、实际指标值、连续超标/恢复计数以及
人类可读的判定依据 `reason`（状态从 X -> Y 的原因），测试日志会打印这些字段。

## 4. HTTP 用法（可选）

启动应用后：

```bash
# 上报埋点
curl -s -XPOST localhost:8080/telemetry/events -H 'Content-Type: application/json' \
  -d '{"api":"/order","status":"FAILURE","latencyMillis":42}'

# 查询窗口聚合
curl -s localhost:8080/telemetry/metrics/order

# 注册规则（failureRate >= 0.5，连续 2 次超标触发，连续 2 次达标恢复）
curl -s -XPOST localhost:8080/telemetry/rules -H 'Content-Type: application/json' \
  -d '{"ruleId":"r1","api":"/order","metricType":"FAILURE_RATE","operator":"GTE",
       "threshold":0.5,"pendingRequired":2,"recoveryRequired":2}'

# 评估
curl -s -XPOST localhost:8080/telemetry/rules/r1/evaluate
```

非法埋点/规则返回 HTTP 400（未知规则 404），响应体形如
`{"error":"invalid_metric","reason":"NEGATIVE_LATENCY","message":"..."}`，原因可区分。

## 5. 验证方法

```bash
mvn test
```

测试共 14 个（全部为进程内测试，无外部依赖），日志中会打印**输入、聚合结果与状态判定依据**：

| 测试类 | 覆盖场景 |
| --- | --- |
| `MetricsRegistryTest` | 计数/比率/均值/最大值/分位数口径；窗口滚动后整桶淘汰、无负值；各类非法埋点按原因码拒绝 |
| `AlertEvaluatorTest` | 四态完整迁移；待确认期抖动取消；触发后恢复抖动不解警；恢复需连续达标；恢复后再超标重回 PENDING；同刻重复评估幂等；非法规则、未知指标、未知规则、时间回退拒绝 |
| `ConcurrencyTest` | 16 线程 × 5000 次写入，总数与真实请求严格一致、无丢失重复；8 线程 × 2000 次同刻评估只推进一次状态 |
| `TelemetryControllerIntegrationTest` | Spring Boot 端到端：埋点→聚合→注册→评估，以及 HTTP 层非法输入的原因码 |

核心逻辑直接使用可推进时钟复现窗口滚动与“抖动→触发→恢复”全过程，结果与执行机器速度无关、可复现。
