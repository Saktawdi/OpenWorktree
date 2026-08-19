# provider 能力

能力名称：provider  
能力目录：`gate-application/src/main/java/gate/application/provider/`，`gate-ports/src/main/java/gate/ports/ProviderRepository.java`，`gate-web/src/main/java/gate/web/ProviderModelFetcher.java`  
业务 owner：Web owner  
技术 owner：Application owner  
最低实施等级：L2  
复核/批准等级：L3  
状态：Implementing

## 1. 边界

- 负责的业务不变量：AI 模型服务商（Provider）与可用模型列表（Model）的元数据管理、上游模型列表同步拉取（`/models` fetch）、服务商凭据引用解耦（仅存 `api_key_ref` 标识，明文由 security 隔离）。
- 明确不负责的内容：密钥实际加密存储与解密（security 负责）、大模型具体业务提示词与生成调用（review/session 负责）。
- 上游/下游能力：上游 `security`（提供凭据注入与安全脱敏）；下游 `review` 与 `session`（选择对应 provider_id 与 model 发起调用）。

## 2. 端口与实现

- 驱动端口：`/api/providers/**`，`/api/providers/{id}/models/fetch`。
- 被动端口：`ProviderRepository`、`ProviderModelFetcher`。
- local 实现：`JdbcProviderRepository`、HTTP 模型抓取器。
- production 实现：支持多租户 Provider 配置与动态模型端点探测。
- 超时、取消、错误码：`USAGE`（服务商不存在或为内置不可变 provider）、`GATE_ERROR_IO`（上游接口拉取失败）。

## 3. 数据与事件

- owner 表/列：`provider`（`id, name, base_url, api_key_ref, type, created_at, updated_at`）、`model`（`provider_id, model_name, updated_at`）。
- 只读投影：`/api/providers` 列表脱敏展示（`credential_configured` 布尔标记，禁止返回秘钥内容）。
- 写入事务边界：单个 Provider 创建/更新与模型替换操作在短事务内完成。
- outbox 事件及 sequence：`provider.created`、`provider.updated`、`provider.models_refreshed`。
- 幂等键和 request digest：`provider.id`。
- 对象存储引用及 GC：无。

## 4. 并发与恢复

- 资源锁/CAS：基于 `id` 唯一主键更新。
- lease/fence：无。
- UNKNOWN_OUTCOME 查询方式：无。
- reconcile：无。
- 节点宕机行为：纯静态配置数据，重启后直接从数据库加载。

## 5. 权限与运营

- RBAC 权限：`Admin` 创建/修改/删除 Provider 与配置；`Developer` 只读查询可用模型列表。
- 审计事件：`provider.create`、`provider.update`、`provider.delete`。
- 指标、trace、日志：模型拉取调用延迟与成功率。
- 告警和 runbook：上游 Provider 鉴权失败或 Endpoint 5xx 告警。
- 成本/配额：无。

## 6. 验收

- 单元/契约/架构测试：`RuntimeAndModelFetchTest`、`ApiRoutes` Provider 路由测试。
- 故障测试：模拟上游模型端点超时与异常返回。
- 容量测试：无。
- API/事件兼容证据：`/api/providers` 接口契约稳定并经过严格脱敏验证。
- 数据库迁移和回滚证据：`V1__init.sql` 基础配置表。
