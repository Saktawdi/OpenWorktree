# Legacy ADR / 旧路径映射

用于消除 `DEBT-004` 知识漂移：旧代码注释中的 `ADR-1/8/9/10/12/14` 与 `架构落地执行文档`、`doc/02`、`doc/03` 等历史路径的当前对应关系。

> 规则：旧编号仅为历史引用，不自动视为 `docs/adr/README.md` 中已 `Accepted` 的 ADR。实现依据以 `docs/architecture/production-architecture.md` 与已 `Accepted` 的 `ADR-001~008` 为准。

## 旧 ADR → 新 ADR

| 旧编号（代码/注释/环境变量） | 旧含义 | 当前对应 | 备注 |
|---|---|---|---|
| ADR-1 / ADR-1/ADR-5 | 禁用 JGit，使用真实 git 二进制；Git 原子性 | `ADR-001` 执行拓扑与工作区重建 + `ADR-003` Git OID CAS | 旧 ADR-1 拆分为拓扑与 CAS 两项 |
| ADR-8 | Web 并发模型、Spring 边界、Jackson 取舍 | `ADR-005` Web 并发模型与阻塞隔离 | 原 gate-web `com.sun.net.httpserver` 选型现归 ADR-005 |
| ADR-9 | Provider/newapi 网关、Secret 不进库/日志 | `ADR-006` Agent 沙箱与 Secret 注入 + `ADR-007` 租户/RBAC/审计 | 密钥与网关逻辑现由 ADR-006/007 覆盖 |
| ADR-10 | HUMAN-domain Web token、Credential 存储 | `ADR-007` 租户/RBAC/审计保留 | HUMAN token 归属安全域 |
| ADR-12 | Agent 会话：Claude/OpenCode 双适配器 | `ADR-001` 执行拓扑（Agent Worker/隔离） | 会话恢复等级与沙箱见 ADR-001/006 |
| ADR-14 | 单项目拓扑 gate.toml/auth repo/clones | `ADR-001` 执行拓扑 | 单项目约束保持不变 |

## 旧文档路径 → 新路径

| 旧路径（注释/提交历史） | 新路径 |
|---|---|
| `架构落地执行文档 §4.3`, `§3.2`, `§7.2` 等 | `docs/architecture/production-architecture.md` 对应章节（模块边界 §5、发布协议 §7、SLO §14） |
| `doc/02-执行文档/...`, `doc/03-走读与总结/...` | `docs/archive/`（仅保留仍被代码/测试引用的历史证据，其余由 Git 历史追溯） |
| `doc/01-立项与调研/...` | `docs/product/product-spec.md` 与 `docs/architecture/review-log.md` |
| `gate.toml` 配置说明中引用的旧 ADR | 参见本映射表与 `docs/adr/README.md` 注册表 |

## 使用约定

- 新增代码禁止直接引用旧编号；必须引用 `ADR-00N` 新编号或 `production-architecture.md` 章节。
- 存量注释可逐步替换为 `Legacy: ADR-9 → ADR-006/007` 形式，本映射为唯一追溯源。
- `GOV-DOC-001` 检查旧路径引用时以本映射为豁免依据，未映射的悬空引用视为错误。
