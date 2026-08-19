# 能力注册表

状态：生效基线（Accepted；各能力实现成熟度以表内状态为准）
维护规则：每个能力必须有唯一 owner、目录、端口、数据/事件边界和验收包；新增能力先登记再编码。

能力注册表统一解决“主规范能力列表”和“所有权目录能力名称”不一致的问题。

| 能力 | 职责边界 | 主要模块/目录 | 事实 owner | 当前状态 | 必备交付物 |
| --- | --- | --- | --- | --- | --- |
| `project` | 项目、仓库、目标 ref 和项目级配置 | `application/project`, `adapters/project` | `project` | Baseline | 端口、project 表、权限、配置迁移 |
| `ticket` | 工单创建、编辑、查询和生命周期 | `application/ticket`, `web/ticket` | `ticket` | Implementing | 状态机、API、审计、幂等 |
| `presubmit` | 快照、tree、base OID、diff 和预提审轮次 | `application/presubmit`, `adapters/git` | `presubmit` | Implementing | Snapshot port、Blob、TOCTOU 测试 |
| `review` | 审核编排、证据、策略判定和 review result | `application/review`, `adapters/engine` | `review` | Implementing | Engine port、证据、Fail-Closed、故障测试 |
| `publish` | intent、授权、Git CAS、发布和 reconcile | `application/publish`, `adapters/git` | `publish` | Implementing | PublishAuthorization、CAS、恢复演练 |
| `session` | Agent 配置、会话、消息和恢复等级 | `application/session`, `adapters/session` | `session` | Implementing | Session port、事件、取消、沙箱 |
| `task` | 任务登记、领取、租约、fence、重试和终态 | `application/task`, `adapters/task` | `task` | Baseline | task schema、claim SQL、reaper、指标 |
| `event` | outbox、task_event 存储、sequence 和 SSE 游标 | `application/event`, `adapters/event`, `web/sse` | `event`（存储）；语义 owner 见事件目录 | Planned | Outbox、事件协议、背压、回放 |
| `provider` | Provider、model 列表和凭据引用配置 | `application/provider`, `adapters/provider` | `provider`（配置）；`security`（Secret） | Implementing | Provider port、Secret 引用、模型刷新 |
| `security` | 身份、RBAC、Secret、审计和密钥生命周期 | `application/security`, `adapters/security` | `security` | Planned | AuthN/Z、KMS、WORM 审计、轮换 |
| `metrics` | 成本、容量和运行指标的派生投影 | `application/metrics`, `adapters/metrics` | `metrics`（派生） | Implementing | projector、指标 schema、陈旧度 |
| `status` | 只读状态聚合、健康、依赖和运行视图 | `application/status`, `web/status` | `status`（投影） | Implementing | read-only port、权限、SLO 视图 |

`runtime`、`health`、`config` 不是独立业务能力：它们属于 `status`（只读运行视图）或 `security/provider`（配置与凭据）。不得为了路由数量创建新的横向能力。

## 能力目录与命名规则

能力代码目录必须位于以下之一：

```text
gate-application/src/main/java/gate/application/<capability>/
gate-ports/src/main/java/gate/ports/<capability>/
gate-adapters/src/main/java/gate/adapters/<capability>/
gate-web/src/main/java/gate/web/<capability>/
```

能力目录只能包含该能力的用例、端口、适配器或驱动映射。跨能力共享的类型必须进入 `gate-domain`（稳定领域值）或明确的端口包，禁止放入 `common/shared/utils`。

每个能力必须有一份实例化文档：

```text
docs/capabilities/<capability>.md
```

实例文档必须按 [`governance.md`](governance.md) 第 3 节模板填写，并链接到 owner、ADR、数据目录、测试和 runbook。

## 事件语义 owner 与存储 owner

`event` 能力拥有事件存储、sequence、outbox Relay 和 SSE 游标；它不拥有所有业务事件的语义。事件的语义 owner 由产生业务状态变化的能力决定：

| 事件类别 | 语义 owner | 存储/传输 owner |
| --- | --- | --- |
| `ticket.*` | `ticket` | `event` |
| `review.*` | `review` | `event` |
| `publish.*` | `publish` | `event` |
| `session.*` | `session` | `event` |
| `task.*` | `task` | `event` |
| `provider/security.*` | 对应能力 | `event` |

语义 owner 决定 payload、状态转换和兼容策略；存储 owner 决定持久化、回放、保留和传输实现。两者不得混写职责。
