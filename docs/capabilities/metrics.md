# metrics 能力

能力名称：metrics  
能力目录：`gate-application/src/main/java/gate/application/MetricsService.java`，`gate-application/src/main/java/gate/application/H1Verdict.java`，`gate-adapters/src/main/java/gate/adapters/metrics/`  
业务 owner：Web owner  
技术 owner：Application owner  
最低实施等级：L2  
复核/批准等级：L3  
状态：Implementing

## 1. 边界

- 负责的业务不变量：成本统计（Token 消耗、费用预算）、H1 假说验证（首次通过率、成本比中位数）、审核耗时度量、系统 SLO 监控投影计算；明确 metrics 是只读派生投影，严禁覆盖或修改领域事实。
- 明确不负责的内容：审核事实判决（review 负责）、工单状态推进（ticket 负责）。
- 上游/下游能力：上游 `review`（提供审核结果与 Telemetry 原始记录）、`session`（提供会话 Token 用量）；下游 Web 前端展示与报表导出。

## 2. 端口与实现

- 驱动端口：`MetricsService` 暴露 `export()` 与 `verdict()`；`/api/metrics` 与 `/api/metrics/h1`。
- 被动端口：`ReviewResultRepository`（只读查询事实）、`MetricsProjector`。
- local 实现：内存聚合计算与 SQLite 投影表。
- production 实现：异步 Projector 写入专用时序/分析库（ClickHouse / PostgreSQL 投影表）。
- 超时、取消、错误码：无阻断性错误，遇异常降级返回 EMPTY/DEGRADED 标记。

## 3. 数据与事件

- owner 表/列：`review_cost_projection` 专用投影表（见 `metrics-projection-design.md` / DEBT-010；事实字段由 review 拥有）。
- 只读投影：`/api/metrics`、`/api/metrics/h1`。
- 写入事务边界：独立投影写入事务，失败绝不阻断核心提审发布主链路。
- outbox 事件及 sequence：无（仅消费上游领域事件）。
- 幂等键和 request digest：基于 `presubmit_id` 幂等刷新。
- 对象存储引用及 GC：无。

## 4. 并发与恢复

- 资源锁/CAS：无。
- lease/fence：无。
- UNKNOWN_OUTCOME 查询方式：无。
- reconcile：支持基于 review_result 历史全量数据随时重新构建投影（Rebuild Projection）。
- 节点宕机行为：无持久状态，随时可从事实表重新计算。

## 5. 权限与运营

- RBAC 权限：`Developer` / `Manager` 查看度量数据。
- 审计事件：`metrics.export`。
- 指标、trace、日志：成本中位数、首次通过率、审核耗时分布。
- 告警和 runbook：成本超预期突增报警。
- 成本/配额：无。

## 6. 验收

- 单元/契约/架构测试：`CostExtractionTest`、`MetricsExportTest`、`H1VerdictTest`。
- 故障测试：原始数据缺失时的降级回退验证。
- 容量测试：百万级历史审核记录的聚合性能测试。
- API/事件兼容证据：`/api/metrics` 契约稳定。
- 数据库迁移和回滚证据：`V10__metrics_projection_expand.sql` 建立独立投影表（DEBT-010）。
