# ADR-007：租户、RBAC、职责分离与审计保留

状态：Accepted  
负责人：Security owner  
批准人：Codex（项目负责人授权 L4）  
创建日期：2026-08-19  
批准日期：2026-08-19  
复审日期：2026-11-17  
验证证据计划：跨租户、水平越权、职责冲突、撤销、审计完整性与保留测试

## 背景与约束

enterprise 模式必须防止 tenant/project/resource 越权，并把开发、审核、发布、策略和运维权限分离。运维身份不能默认获得业务审批权。

## 选项与取舍

- 仅项目级 token：无法表达租户与职责分离，拒绝。
- 完全外包给网关：无法保护后台任务、事件回放和内部端口，拒绝。
- 网关认证 + 应用内 tenant/RBAC 授权 + 不可变审计：采用。

## 决策

- 所有生产资源、任务、事件和对象引用绑定 `tenant_id + project_id`；每次 API、SSE 重连和后台执行重新校验归属。
- 角色至少为 Developer、Reviewer、Publisher、ProjectAdmin、SecurityAuditor、SystemOperator；默认最小权限。
- 高风险项目强制策略修改、审核和发布职责分离；例外需双人批准、到期日和不可变审计。
- 审计记录 actor、tenant、project、action、resource、result、reason、trace/request id、策略版本和前序完整性信息。
- enterprise 审计进入独立信任域 WORM/KMS checkpoint；默认在线可查 365 天，法规/高风险项目可配置更长且不得低于适用合规要求。
- 单租户迁移通过 expand/backfill/contract，为现有数据分配明确默认 tenant；未知归属数据隔离，禁止 NULL 绕过授权。

## 负面后果

查询、索引、缓存和事件均需携带租户维度；职责分离会降低单人高风险发布便利性。

## 迁移、回滚与验证

先增加 tenant 字段和双读校验，再启用强制授权，最后收紧非空约束。启用后不得回退为仅前端隐藏。验证跨租户 ID、cursor、对象引用、任务接管、角色组合、权限撤销和审计链损坏。
