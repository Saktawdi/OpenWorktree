# status 能力

能力名称：status  
能力目录：`gate-application/src/main/java/gate/application/status/`，`gate-web/src/main/java/gate/web/StatusRoutes.java`，`gate-web/src/main/java/gate/web/RuntimeInfoService.java`  
业务 owner：Web owner  
技术 owner：Application owner  
最低实施等级：L1  
复核/批准等级：L2  
状态：Implementing

## 1. 边界

- 负责的业务不变量：系统运行环境探测（JDK、OS、Git 版本、本地 CLI 可用性）、全局分支 Tip 聚合、工单状态大盘只读投影、配置信息安全脱敏展示（严禁泄露密钥与私密配置）。
- 明确不负责的内容：业务事实表直接写入、业务状态流转控制。
- 上游/下游能力：上游 `ticket`、`project`、`presubmit`、`publish`（获取各实体最新状态与 Git 观测结果）；下游 Web 监控大盘与管理控制台。

## 2. 端口与实现

- 驱动端口：`StatusRoutes` 暴露的 `/api/status`、`/api/runtime`、`/api/agent-runtimes`、`/api/config`。
- 被动端口：`GateService.status`、`RefObserver`、`RuntimeInfoService`。
- local 实现：本地进程环境反射与 Git 目录检查。
- production 实现：Kubernetes Liveness/Readiness 探针与集群节点监控集成。
- 超时、取消、错误码：Git 不可用时降级返回空 Tip，不抛出阻断性 500。

## 3. 数据与事件

- owner 表/列：无（纯只读派生投影能力）。
- 只读投影：`/api/status` 聚合投影。
- 写入事务边界：无写入。
- outbox 事件及 sequence：无。
- 幂等键和 request digest：无。
- 对象存储引用及 GC：无。

## 4. 并发与恢复

- 资源锁/CAS：无。
- lease/fence：无。
- UNKNOWN_OUTCOME 查询方式：无。
- reconcile：无。
- 节点宕机行为：无状态，随服务实例启动即用。

## 5. 权限与运营

- RBAC 权限：`Developer` / `Operator` 访问状态大盘；脱敏视图全员可读。
- 审计事件：`status.query`。
- 指标、trace、日志：探针健康状态、Git 连通性度量。
- 告警和 runbook：权威仓库不可达告警。
- 成本/配额：无。

## 6. 验收

- 单元/契约/架构测试：`WebReadOnlyApiTest`、`RuntimeAndModelFetchTest`。
- 故障测试：模拟缺少 auth.git 或 Git 未安装时的优雅降级。
- 容量测试：高频健康检查请求压力测试。
- API/事件兼容证据：`/api/status`、`/api/runtime` 契约稳定。
- 数据库迁移和回滚证据：无 DB 表。
