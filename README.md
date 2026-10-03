# telemetry-alerting-java（bootstrap-a）

进程内的接口调用指标采集与告警判定组件，基于 Spring Boot 3 / Java 21，**不依赖任何外部服务、数据库或真实凭据**。
全部状态保存在内存中，并发写入与评估结果确定、可复现（测试使用可手动推进的 `FixedClock` 驱动时间）。

本轮新增能力（面向线上复盘与离线补数）：

- **历史补报**：`backfill(...)` / `POST /telemetry/events/backfill`，乱序、早于已见数据的事件回填所属时间桶，附回执；
- **按时间点回看**：`snapshotAt(api, t)` / `GET /telemetry/metrics/{api}?at=...`，看某历史时刻的窗口聚合；
- **容量约束**：每接口保留上限，最旧先淘汰，淘汰量单独暴露，内存有界。

## 1. 模块结构

```
core/    Clock（SystemClock / FixedClock）：时间抽象，测试可精确控制窗口滚动
metrics/ CallStatus、MetricType、CallEvent、WindowSnapshot、BackfillReceipt、
         MetricsRegistry、InvalidMetricException
alert/   AlertState、ComparisonOperator、AlertRule、AlertEvaluation、AlertEvaluator、InvalidAlertRuleException
web/      TelemetryConfiguration + TelemetryController（可选的薄 HTTP 入口）
```

- `MetricsRegistry`：滚动时间窗口计数器，线程安全；支持历史补报、按时刻回看、每接口容量上限。
- `AlertEvaluator`：基于窗口指标的规则状态机，线程安全、评估幂等。

## 2. 指标口径

### 2.1 滚动窗口、时间桶与保留期

- 窗口长度 `windowMillis`（默认 60000ms），桶宽 `bucketMillis`（默认 1000ms），可用
  `telemetry.window-millis` / `telemetry.bucket-millis` 覆盖。
- 窗口为左闭右开 **`[now-window, now)`**。每个桶覆盖 `[bucketStart, bucketStart+bucket)`。
- **保留期** `retentionMillis`（默认 = `windowMillis`，可用 `telemetry.retention-millis` 覆盖，
  必须 >= 窗口长度）：桶与 `[now-retention, now]` 不再相交时被整体淘汰。
  保留期决定**历史补报能补多老的数据**、**按时刻回看能追溯多久**；
  默认配置下行为与旧版完全一致（只能看当前窗口）。
- 桶内**逐事件存储**（按事件时间戳有序），聚合计数永远由现存事件现算，
  不存在"先加后减"的中间态，**任何时刻读到的计数都不可能为负**。
- 每个接口一把独立锁（`ApiWindow` 内置 synchronized），不同接口互不阻塞；
  "淘汰过期桶 + 写入/读取"在同一把锁内完成，并发写入**不丢失、不重复**。

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

窗口内数据全部过期后，`snapshot(api)` 返回空 `Optional`，与"接口从未上报"一致；
调用方（如告警评估）据此区分"无数据"，不会把无数据静默当作达标。

### 2.3 非法埋点处置（拒绝，不静默计入）

`record(...)` 对每条埋点做严格校验，非法输入抛 `InvalidMetricException` 并携带原因码：

| 原因码 | 触发条件 |
| --- | --- |
| `BLANK_API` | 接口名为 null 或空白 |
| `NULL_STATUS` | 调用状态缺失或无法识别为 SUCCESS/FAILURE |
| `NEGATIVE_LATENCY` | 延迟为负 |
| `LATENCY_TOO_LARGE` | 延迟超过 24h 上限（脏数据） |
| `TIMESTAMP_IN_FUTURE` | 事件时间戳晚于当前时钟 |
| `TIMESTAMP_TOO_OLD` | 实时写入：事件时间戳已在窗口之外，或乱序事件无法回填到更早的桶；补报：时间戳超出保留期 |
| `NULL_EVENT` | 事件对象为 null |

被拒绝的埋点不会产生任何计数。

## 3. 历史补报（新增）

旁路日志批量捞回的历史埋点，通过 `MetricsRegistry.backfill(Collection<CallEvent>)`
或 `POST /telemetry/events/backfill` 写入：

- **乱序与迟到都接受**：批内顺序任意，时间戳可以早于已见数据，事件回填到其本来所属的
  时间桶（桶按 `TreeMap` 组织，支持任意位置插入）。与实时 `record()` 的唯一区别是
  不做"乱序拒绝"，其余校验（未来时间戳、字段合法性）一致；
- **一致性口径**：补报事件与当时实时写入走完全相同的归桶与聚合逻辑，补报完成后同一
  时间窗口的聚合（次数、成功/失败、延迟分布）与"当时就近实时写入"的结果一致；
- **保留期约束**：时间戳早于 `now - retentionMillis` 的事件无法补入，逐条拒绝并计入回执；
- **与实时写入并存**：按接口分组、每组分小块（128 条）持锁处理，块间释放锁，
  同接口实时写入可正常穿插，不同接口完全并行；
- **回执**（`BackfillReceipt`）：`acceptedCount` / `rejectedCount`、逐条拒绝明细
  （原批次下标 + 原因码，按下标排序）、`acceptedByBucket`（每接口落入各时间桶的条数）。

```bash
curl -s -XPOST localhost:8080/telemetry/events/backfill -H 'Content-Type: application/json' -d '{
  "events":[
    {"api":"/order","status":"SUCCESS","latencyMillis":42,"timestampMillis":1759540000000},
    {"api":"/order","status":"FAILURE","latencyMillis":87,"timestampMillis":1759539995000}
  ]}'
# => {"acceptedCount":2,"rejectedCount":0,"rejections":[],
#     "acceptedByBucket":{"/order":{"1759540000000":1,"1759539995000":1}}}
```

## 4. 按时间点回看（新增）

`MetricsRegistry.snapshotAt(api, t)` 或 `GET /telemetry/metrics/{api}?at={epochMillis}`：

- 返回"若在时刻 t 取快照"会得到的窗口聚合，窗口为 `[t-window, t]`，
  只统计事件时间戳 `<= t` 的数据，**之后新写入（时间戳更晚）的数据不影响结果**；
- 同一 t 反复查询结果稳定一致；未来时刻（`t > now`）以 `TIMESTAMP_IN_FUTURE` 拒绝；
- 回看能力受**保留期与容量上限**约束：已被淘汰的历史数据无法还原，
  超出保留期的时刻返回空（与"窗口内无数据"口径一致）；
- 若回看之后又有**补报**落入该时刻窗口（这本来就是补全历史），回看会反映补全后的数据，
  与"当时就近实时写入"的口径一致；补报完成后结果再次稳定。

## 5. 容量约束（新增）

- 每接口可设保留上限（事件条数）：`registry.setMaxEvents(api, n)` 或
  `PUT /telemetry/metrics/{api}/capacity {"maxEvents":n}`；`n <= 0` 表示不限。
  全局默认值用 `telemetry.max-events-per-api` 配置（默认 0 = 不限，与旧版行为一致）；
- **淘汰规则固定可预期**：超过上限时按"桶从早到晚、桶内按事件时间戳从旧到新"逐条淘汰，
  即始终优先保留最新的数据；
- **淘汰量单独暴露**：`registry.evictedCount(api)` 或
  `GET /telemetry/metrics/{api}/capacity`（返回 `retainedEvents` 与 `evictedByCapacity`），
  计数单调不减；
- **口径保证**：淘汰只删除事件，聚合计数由现存事件现算，不出现负值；
  恒有 `保留数 <= 上限`、`淘汰数 = 历史总入桶 - 当前保留`；
  批量补报与容量淘汰同时发生时上述口径依然成立（补报先入桶，再按同一规则收敛）。

## 6. 告警状态迁移规则

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
  状态与"连续超标/连续恢复"计数不发生任何变化（见
  `AlertEvaluatorTest.repeatedEvaluationAtSameTimeIsIdempotent` 与并发评估测试）。
  评估时刻必须单调非递减，回退将以 `NON_MONOTONIC_EVALUATION_TIME` 拒绝。

### 6.1 非法规则 / 无法评估的处置

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

## 7. HTTP 用法（可选）

启动应用后：

```bash
# 上报埋点（既有方式，不变）
curl -s -XPOST localhost:8080/telemetry/events -H 'Content-Type: application/json' \
  -d '{"api":"/order","status":"FAILURE","latencyMillis":42}'

# 查询当前窗口聚合（既有方式，不变）
curl -s localhost:8080/telemetry/metrics/order

# 按历史时刻回看（新增，at 为 epoch 毫秒）
curl -s 'localhost:8080/telemetry/metrics/order?at=1759540000000'

# 批量历史补报（新增）
curl -s -XPOST localhost:8080/telemetry/events/backfill -H 'Content-Type: application/json' \
  -d '{"events":[{"api":"/order","status":"SUCCESS","latencyMillis":10,"timestampMillis":1759539990000}]}'

# 设置 / 查询容量上限（新增）
curl -s -XPUT localhost:8080/telemetry/metrics/order/capacity \
  -H 'Content-Type: application/json' -d '{"maxEvents":100000}'
curl -s localhost:8080/telemetry/metrics/order/capacity

# 注册规则（failureRate >= 0.5，连续 2 次超标触发，连续 2 次达标恢复）
curl -s -XPOST localhost:8080/telemetry/rules -H 'Content-Type: application/json' \
  -d '{"ruleId":"r1","api":"/order","metricType":"FAILURE_RATE","operator":"GTE",
       "threshold":0.5,"pendingRequired":2,"recoveryRequired":2}'

# 评估
curl -s -XPOST localhost:8080/telemetry/rules/r1/evaluate
```

非法埋点/规则返回 HTTP 400（未知规则 404），响应体形如
`{"error":"invalid_metric","reason":"NEGATIVE_LATENCY","message":"..."}`，原因可区分。

## 8. 兼容性说明

- 既有调用方式**完全兼容**：`record(...)` / `POST /telemetry/events`、
  `snapshot(api)` / `GET /telemetry/metrics/{api}`、规则注册与评估的签名和语义不变；
- 默认配置（`retention-millis = window-millis`、`max-events-per-api = 0`）下行为与旧版一致：
  数据滚出窗口即淘汰、不设容量上限；
- 实时 `record()` 对乱序事件仍按 `TIMESTAMP_TOO_OLD` 拒绝（保持既有口径），
  历史数据请走补报通道；
- 内部存储由"桶内计数器"改为"桶内逐事件"，聚合结果口径不变（既有测试全部回归通过）。

## 9. 验证方法

```bash
mvn test
```

测试共 25 个（全部为进程内测试，无外部依赖），日志中会打印**输入、聚合结果与判定依据**：

| 测试类 | 覆盖场景 |
| --- | --- |
| `MetricsRegistryTest` | 计数/比率/均值/最大值/分位数口径；窗口滚动后整桶淘汰、无负值；各类非法埋点按原因码拒绝 |
| `BackfillTest`（新增） | 乱序补报与实时参照组聚合严格一致（次数/成败/延迟分布）；回执的接受数、落桶分布、逐条拒绝原因；4 线程实时写入与分批乱序补报并发混合，不丢失不重复 |
| `PointInTimeSnapshotTest`（新增） | 回看只含该时刻及所在窗口数据、与当时实时快照一致；后续写入不影响、反复查询稳定；补报落入该窗口后反映补全历史；未来时刻拒绝、超保留期返回空 |
| `CapacityEvictionTest`（新增） | 到顶后最旧先淘汰、淘汰量单独暴露、无负值；补报+容量并存时账目平衡（淘汰=入桶-保留）；每接口上限独立、0 表示不限 |
| `AlertEvaluatorTest` | 四态完整迁移；待确认期抖动取消；触发后恢复抖动不解警；恢复需连续达标；恢复后再超标重回 PENDING；同刻重复评估幂等；非法规则、未知指标、未知规则、时间回退拒绝 |
| `ConcurrencyTest` | 16 线程 × 5000 次写入，总数与真实请求严格一致、无丢失重复；8 线程 × 2000 次同刻评估只推进一次状态 |
| `TelemetryControllerIntegrationTest` | Spring Boot 端到端：埋点→聚合→注册→评估；补报/回看/容量三个新端点；HTTP 层非法输入的原因码 |

核心逻辑直接使用可推进时钟复现窗口滚动与"抖动→触发→恢复"全过程，结果与执行机器速度无关、可复现。

