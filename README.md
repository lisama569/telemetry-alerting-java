# telemetry-alerting-java（bootstrap-a）

进程内的接口调用指标采集与告警判定组件，基于 Spring Boot 3 / Java 21，**不依赖任何外部服务、数据库或真实凭据**。
全部状态保存在内存中，并发写入与评估结果确定、可复现（测试使用可手动推进的 `FixedClock` 驱动时间）。

## 1. 模块结构

```
core/    Clock（SystemClock / FixedClock）：时间抽象，测试可精确控制窗口滚动
metrics/ CallStatus、MetricType、CallEvent、WindowSnapshot、MetricsRegistry、InvalidMetricException
         BackfillReport（补报回执）、EvictionStats（容量淘汰统计）
alert/   AlertState、ComparisonOperator、AlertRule、AlertEvaluation、AlertEvaluator、InvalidAlertRuleException
web/      TelemetryConfiguration + TelemetryController（可选的薄 HTTP 入口）
```

- `MetricsRegistry`：滚动时间窗口计数器，线程安全；支持乱序历史补报、按时间点回看、每接口容量上限。
- `AlertEvaluator`：基于窗口指标的规则状态机，线程安全、评估幂等。

## 2. 指标口径

### 2.1 滚动窗口与时间桶

- 窗口长度 `windowMillis`（默认 60000ms），桶宽 `bucketMillis`（默认 1000ms），可用
  `telemetry.window-millis` / `telemetry.bucket-millis` 覆盖。
- 窗口为左闭右开 **`[now-window, now)`**。每个桶覆盖 `[bucketStart, bucketStart+bucket)`，
  与窗口不再相交（`桶结束 <= now-window`）的桶在下次写入/聚合时被整体淘汰。
- 每个接口的桶按桶起点排序存放（`TreeMap`），桶内直接保存事件本身；聚合 = 对现存桶内
  事件现算求和，计数只有单一数据源，**任何时刻读到的计数都不可能为负**，
  淘汰（窗口滚动或容量上限）也不会让进行中的统计错乱。
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
| `TIMESTAMP_TOO_OLD` | 事件时间戳已在窗口之外，或实时通道的乱序事件无法回填到更早的桶 |
| `NULL_EVENT` | 事件对象为 null |
| `NULL_TIMESTAMP` | 补报事件缺少时间戳（历史数据必须携带事件时间，仅补报入口） |
| `INVALID_MAX_SAMPLES` | 运行期设置的保留上限不是正数（仅上限设置入口；恢复默认请用清除接口） |

被拒绝的埋点不会产生任何计数。

### 2.4 历史补报（`backfill`）

旁路日志批量捞回的历史埋点，通过 `MetricsRegistry.backfill(List<CallEvent>)`
（或 `POST /telemetry/events/backfill`）写入：

- **乱序友好**：批内顺序任意、时间戳可以早于已见数据，事件会被归入其本来所属的时间桶
  （桶按时间排序存放，可插入中间位置）。实时通道 `record(...)` 的乱序拒绝语义保持不变。
- **口径一致**：校验规则、原因码与实时写入完全相同（仍要求落在 `[now-window, now]` 内）；
  补报完成后，同一窗口的聚合结果与这些调用当时就近实时写入得到的结果**完全一致**
  （见 `BackfillTest.outOfOrderBackfillConvergesWithRealtimeIngestion`）。
- **与实时并存**：每条事件在独立的短临界区内写入（接口级锁），批量补报不会长时间
  阻塞同一接口的实时写入，不同接口互不阻塞。
- **逐条容错**：单条非法不拖垮整批，非法条目记入回执而非抛异常。
- **回执**（`BackfillReport`）：`received`（本批总数）、`accepted`（接受数）、
  `slots`（接受事件按 `(接口, 桶起点)` 汇总的落点明细）、
  `rejections`（每条拒绝的原始下标 + 原因码 + 说明）。

### 2.5 按时间点回看（`snapshotAt`）

`MetricsRegistry.snapshotAt(api, timestampMillis)`（或
`GET /telemetry/metrics/{api}/at?timestampMillis=...`）返回历史时刻 `t` 所在窗口
`[t-window, t]` 的聚合：

- 只统计事件时间戳 **不超过 t** 的数据，之后的新写入（事件时间更晚）不影响结果；
  窗口左界与实时口径一致（桶粒度）。因此 `t` 时刻的实时快照与事后的 `snapshotAt(t)`
  完全一致（见 `SnapshotAtTest.snapshotAtMatchesRealtimeSnapshotTakenAtSameMoment`）。
- 同一时刻反复查询结果稳定；未来时刻以 `TIMESTAMP_IN_FUTURE` 拒绝。
- 回看范围受保留期约束：数据随窗口滚动（或容量淘汰）销毁后，对应历史时刻返回空
  `Optional`（HTTP 404），与“窗口内无指标”一致，结果同样稳定。

### 2.6 容量约束（每接口保留上限）

`telemetry.max-samples-per-api`（默认 `0` = 不限，兼容既有行为）是所有接口的**默认**
保留上限，防止长时间运行内存无限增长；此外可以为**单个接口**在运行期单独设置上限：

- **按接口设置 / 查询 / 清除（运行期生效，无需重启）**：
  - `MetricsRegistry.setMaxSamplesPerApi(api, n)` / `PUT /telemetry/metrics/{api}/max-samples`
    为单个接口设置上限 `n`，**立即生效**：后续写入按新上限淘汰；若当前保留量已超过
    新上限，立即按”最旧样本优先”淘汰到位（计入容量淘汰统计）。
  - `MetricsRegistry.maxSamplesPerApi(api)` / `GET /telemetry/metrics/{api}/max-samples`
    查询该接口**当前生效**的上限（覆盖值或默认值），响应同时带出默认值与是否被覆盖。
  - `MetricsRegistry.clearMaxSamplesPerApi(api)` / `DELETE /telemetry/metrics/{api}/max-samples`
    清除覆盖、恢复默认上限，立即生效。
  - 未单独设置的接口一律走默认值，互不影响。
- **无效上限明确拒绝**：运行期设置的上限必须为正数，`0` 或负数抛
  `InvalidMetricException`（原因码 `INVALID_MAX_SAMPLES`，HTTP 400），
  已生效的上限与全部统计保持不变。想恢复”不限/默认”请用清除接口，而不是设 0。
- **淘汰规则固定可预期**：超限后按”最旧样本优先”逐条淘汰（时间最早的桶、桶内最早
  写入的事件），空桶整体移除。只减真实存在的事件，计数恒为非负。
- **淘汰量按接口单独暴露**：`MetricsRegistry.evictionStats(api)`（或
  `GET /telemetry/metrics/{api}/evictions`）返回 `EvictionStats`，其中
  `maxSamplesPerApi` 是**该接口自己生效的上限**（覆盖值或默认值），不会所有接口
  报成同一个值。窗口随时间自然滚动的淘汰**不计入**；
  任意时刻满足 `写入总数 = 保留量 + 容量淘汰量 + 自然滚动淘汰量`。
- **并发一致**：上限调整、容量淘汰、批量补报同时发生时，覆盖项的写入与按新上限的
  淘汰在同一接口锁临界区内完成，任何时刻读到的 `(生效上限, 保留量, 淘汰量)` 都是
  自洽的（保留量恒不超过生效上限、计数非负、总量守恒），见
  `PerApiCapacityTest.limitChangeCapacityEvictionAndBackfillConcurrentlyKeepAccountingConsistent`。
- **与补报并存**：补报回执的 `accepted` 按接受口径统计，与容量淘汰分开；补报触发
  超限同样按最旧优先淘汰，上述守恒口径依然成立
  （见 `CapacityEvictionTest.backfillAndCapacityEvictionTogetherKeepAccountingConsistent`）。

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

# 历史补报（乱序、时间戳早于已见数据；timestampMillis 必填）
curl -s -XPOST localhost:8080/telemetry/events/backfill -H 'Content-Type: application/json' \
  -d '{"events":[
        {"api":"/order","status":"SUCCESS","latencyMillis":30,"timestampMillis":1791036400000},
        {"api":"/order","status":"FAILURE","latencyMillis":50,"timestampMillis":1791036390000}
      ]}'
# -> {"received":2,"accepted":2,"slots":[{"api":"/order","bucketStartMillis":...,"acceptedCount":...}],"rejections":[]}

# 按时间点回看（t 时刻所在窗口的聚合，不受之后写入影响）
curl -s 'localhost:8080/telemetry/metrics/order/at?timestampMillis=1791036400000'

# 容量淘汰统计（需先设 telemetry.max-samples-per-api 或按接口设置上限）
curl -s localhost:8080/telemetry/metrics/order/evictions

# 运行期为单个接口设置保留上限（立即生效，无需重启）
curl -s -XPUT localhost:8080/telemetry/metrics/order/max-samples \
  -H 'Content-Type: application/json' -d '{"maxSamples":100}'

# 查询该接口当前生效的上限（覆盖值或默认值）
curl -s localhost:8080/telemetry/metrics/order/max-samples
# -> {"api":"order","maxSamplesPerApi":100,"defaultMaxSamplesPerApi":0,"overridden":true}

# 清除覆盖，恢复默认上限
curl -s -XDELETE localhost:8080/telemetry/metrics/order/max-samples
```

非法埋点/规则返回 HTTP 400（未知规则 404），响应体形如
`{"error":"invalid_metric","reason":"NEGATIVE_LATENCY","message":"..."}`，原因可区分。
补报批次本身始终返回 200 + 回执，单条非法只体现在回执的 `rejections` 里。

## 5. 兼容性

- `MetricsRegistry(windowMillis, bucketMillis, clock)` 三参构造、`record(...)`、
  `snapshot(api)`、全部原因码与拒绝语义**保持不变**，既有调用方无需修改；
  实时通道仍拒绝乱序回填（乱序历史数据请走 `backfill`）。
- 默认 `telemetry.max-samples-per-api=0`（不限容量），行为与上一版完全一致；
  只有显式设置上限（全局默认或按接口覆盖）后才启用容量淘汰。
- 新增能力均为纯增量 API：`backfill` / `snapshotAt` / `evictionStats` /
  `setMaxSamplesPerApi` / `maxSamplesPerApi(api)` / `clearMaxSamplesPerApi` /
  `hasMaxSamplesOverride` 及对应 HTTP 端点，既有调用方无需修改。

## 6. 验证方法

```bash
mvn test
```

测试共 30 个（全部为进程内测试，无外部依赖），日志中会打印**输入、聚合结果与判定依据**：

| 测试类 | 覆盖场景 |
| --- | --- |
| `MetricsRegistryTest` | 计数/比率/均值/最大值/分位数口径；窗口滚动后整桶淘汰、无负值；各类非法埋点按原因码拒绝 |
| `BackfillTest` | 乱序补报与实时写入殊途同归（聚合逐字段一致）；回执的落点明细与拒绝原因码；补报与实时并发写入不丢失不重复 |
| `SnapshotAtTest` | 回看只反映该时刻及所在窗口数据；之后写入不影响、反复查询稳定；与当时实时快照一致；未来时刻拒绝、滚出保留期返回空 |
| `CapacityEvictionTest` | 到顶后最旧样本优先淘汰、淘汰量单独暴露；并发读写下无负值、统计自洽、总量守恒；补报+容量淘汰并存口径；默认不限容量兼容 |
| `PerApiCapacityTest` | 不同接口不同上限、未设置接口走默认值；运行期调小/调大/清除立即生效；无效上限（0/负数）拒绝且不改现状；上限调整+容量淘汰+批量补报并发时账目守恒、无负值 |
| `AlertEvaluatorTest` | 四态完整迁移；待确认期抖动取消；触发后恢复抖动不解警；恢复需连续达标；恢复后再超标重回 PENDING；同刻重复评估幂等；非法规则、未知指标、未知规则、时间回退拒绝 |
| `ConcurrencyTest` | 16 线程 × 5000 次写入，总数与真实请求严格一致、无丢失重复；8 线程 × 2000 次同刻评估只推进一次状态 |
| `TelemetryControllerIntegrationTest` | Spring Boot 端到端：埋点→聚合→注册→评估，以及 HTTP 层非法输入的原因码 |
| `TelemetryBackfillIntegrationTest` | 新端点端到端：补报回执（含 NULL_TIMESTAMP/NULL_STATUS 容错）、回看稳定性、容量上限装配与淘汰统计 |
| `PerApiCapacityIntegrationTest` | 运行期按接口设置/查询/清除上限的 HTTP 端到端：默认值兜底、立即生效、无效上限 400 且不改动现状、清除后恢复默认 |

核心逻辑直接使用可推进时钟复现窗口滚动与“抖动→触发→恢复”全过程，结果与执行机器速度无关、可复现。
