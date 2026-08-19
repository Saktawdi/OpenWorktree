# security 能力

能力名称：security  
能力目录：`gate-application/src/main/java/gate/application/security/`，`gate-ports/src/main/java/gate/ports/AuditLog.java`，`gate-web/src/main/java/gate/web/AuthFilter.java`，`gate-web/src/main/java/gate/web/WebToken.java`  
业务 owner：Web owner  
技术 owner：Application owner  
最低实施等级：L3  
复核/批准等级：L4  
状态：Planned

## 1. 边界

- 负责的业务不变量：调用方身份认证（WebToken / API Key）、细粒度 RBAC 权限判定、多租户（Tenant）隔离策略、敏感 Secret 安全管理（KMS 集成、脱敏与不落地）、不可篡改审计日志（AuditLog WORM）。
- 明确不负责的内容：具体业务工单与审核流转（由 ticket/review 执行）。
- 上游/下游能力：上游 所有 HTTP / CLI / MCP 请求入口；下游 各业务能力（提供受控的主体 Context 与授权判断）。

## 2. 端口与实现

- 驱动端口：`AuthFilter` 安全过滤器、`WebToken` 校验与生成。
- 被动端口：`AuditLog`（审计日志持久化）、`SecretVaultPort`。
- local 实现：本地 Token 文件（`human_token`）、`AuthFilter` 单租户鉴权、SQLite / 文件追加 `AuditLog`。
- production 实现：OAuth2 / OIDC SSO、RBAC 授权引擎、HashiCorp Vault / AWS KMS 秘钥管理、只写不可篡改审计库。
- 超时、取消、错误码：`UNAUTHENTICATED`（未认证或 Token 无效）、`PERMISSION_DENIED`（权限不足）。

## 3. 数据与事件

- owner 表/列：`audit_event` 表（`id, timestamp, kind, ticket_no, review_round, payload_json, prev_sha256, signature`）。
- 只读投影：审计日志合规查询。
- 写入事务边界：审计事件只追加写入。
- outbox 事件及 sequence：`security.auth_failure`、`security.permission_denied`。
- 幂等键和 request digest：无。
- 对象存储引用及 GC：长期审计归档。

## 4. 并发与恢复

- 资源锁/CAS：无。
- lease/fence：无。
- UNKNOWN_OUTCOME 查询方式：无。
- reconcile：无。
- 节点宕机行为：无状态认证，恢复后读取配置与密钥即可。

## 5. 权限与运营

- RBAC 权限：`SecAdmin` 管理安全策略与密钥；全量接口强制鉴权。
- 审计事件：自身记录所有鉴权与权限变更事件。
- 指标、trace、日志：认证失败率、越权拦截次数、Secret 访问频次。
- 告警和 runbook：短时间高频 401/403 触发安全风控报警。
- 成本/配额：防暴破限流。

## 6. 验收

- 单元/契约/架构测试：`WebAuthTest`、`PermissionDomainTest`。
- 故障测试：无效 Token、跨租户越权与防重放测试。
- 容量测试：高并发认证过滤器性能基准测试。
- API/事件兼容证据：标准 HTTP Bearer Header 兼容。
- 数据库迁移和回滚证据：`V1__init.sql` 审计表，后续接入 Phase 4 企业级扩展。
