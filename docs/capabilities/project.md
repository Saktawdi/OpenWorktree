# project 能力

能力名称：project
能力目录：`gate-application/src/main/java/gate/application/project/`，`gate-ports/src/main/java/gate/ports/ProjectRepository.java`，`gate-web/src/main/java/gate/web/project/`，`gate-adapters/src/main/java/gate/adapters/store/JdbcProjectRepository.java`
业务 owner：Web owner
技术 owner：Application owner
最低实施等级：L2
复核/批准等级：L3
状态：Baseline → Implementing（本次受控实施中提升）

## 1. 边界

- 负责的业务不变量：
  - `project.id` 全局唯一，`name` 非空
  - `authRepo/targetRef/clonesRoot` 三元组与 gate.toml 中 `[project]` 配置一致，`TopologyInitializer.createClone` 幂等创建
  - workspace 路径与 clone 物理隔离（每个 project 单独立目录）
- 明确不负责的内容：
  - ticket 内部状态机、presubmit 快照、review 判决、publish CAS（仅通过 `project_id` 关联）
  - metrics 派生、session 沙箱
- 上游/下游能力：
  - 上游 `security`（租户/配置来源）、`status` 只读聚合依赖 project 事实
  - 下游 `ticket`（`ticket.project_id` FK）、`presubmit`（通过 ticket 间接关联 project）

## 2. 端口与实现

- 驱动端口：
  - `ProjectRepository.find/findAll/insert/update/delete`
  - `TopologyInitializer`（创建 clone/worktree）
- 被动端口：
  - JDBC `JdbcProjectRepository`（SQLite `project` 表）
- local 实现：
  - SQLite，`project` 表 `id/name/repo_path/target_ref/clones_root`
  - `TomlGateConfigLoader` 加载 `gate.toml [project]` → 写入 project 表
- production 实现：
  - PostgreSQL 同 schema（当前 `gate-adapters` 仅提供 SQLite，team 模式需 PG 适配器，关联 DEBT-005）
- 超时、取消、错误码：
  - `USAGE`：重复 `project.id`、缺失 `authRepo`、非法 `targetRef`
  - 无长任务，不涉及 lease/fence

## 3. 数据与事件

- owner 表/列：`project`（`id, name, repo_path, target_ref, clones_root, created_at, updated_at`）— 唯一 owner
- 只读投影：`status` 通过 `projectRepository.findAll` 聚合项目列表
- 写入事务边界：单 `insert/update` 短事务 ≤100ms，不跨 Git（Git clone 在事务外）
- outbox 事件及 sequence：`project.created/updated/deleted`（由 project 事务 + outbox，`event` 存储，语义归 project）
- 幂等键和 request digest：`project.id` 为幂等键；重复 `POST /api/projects` 同 id 返回 `USAGE`
- 对象存储引用及 GC：无；workspace 为本地目录，由 `TopologyInitializer` 管理，删除 project 时需手动清理（后续企业模式改为对象存储快照）

## 4. 并发与恢复

- 资源锁/CAS：`project.id` 唯一约束；`update` 基于 `id` 单行 CAS
- lease/fence：无
- UNKNOWN_OUTCOME：无（Git clone 幂等，重复 `createClone` 返回已存在路径）
- reconcile：重启后 `reconcile` 不涉及 project（无 intent）
- 节点宕机行为：无持久任务，重启后读 `project` 表即恢复

## 5. 权限与运营

- RBAC 权限：`Developer` 创建/查询；`ProjectAdmin` 删除/更新；当前为单租户简化，多租户后由 `security` 能力校验 `tenant_id`
- 审计事件：`project.created` / `project.deleted` 记录 actor 与 `project.id`
- 指标、trace、日志：`project.count` 指标，trace 携带 `project_id`
- 告警和 runbook：`project not found` → `USAGE`；`clone_path not exists` 触发 `TopologyInitializer` 重建，runbook 见 `production-architecture.md §11`
- 成本/配额：无

## 6. 验收

- 单元/契约/架构测试：
  - `JdbcProjectRepository` 单元测试（CRUD + 唯一约束）
  - `ApiRoutes` `/api/projects/**`、`/api/workspaces/**` 契约测试，路径兼容 `projectId` 前缀校验
  - ArchUnit `gate-web/project` 仅依赖 `gate.ports`/`gate.domain`，不直接依赖 `gate.adapters.store` 实现（通过 port）
- 故障测试：无（非长任务）
- 容量测试：无
- API/事件兼容证据：`project.created` 事件 schema 版本化，OpenAPI 中 `/api/projects` 保持 `id/name` 必选
- 数据库迁移和回滚证据：`V6__project_meta.sql` 扩展字段，`expand` 增加可空列，`contract` 阶段删旧列，兼容滚动升级（见 `verification-contract.md GOV-DB-001`）
