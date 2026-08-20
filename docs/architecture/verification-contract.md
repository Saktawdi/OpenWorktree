# 架构验证命令契约

状态：生效命令契约（7 项已实现、1 项部分实现、2 项待实现）
用途：为 `GOV-*` 规则固定执行命令、输入基线、输出和当前落地状态。命令未实现时不得把对应治理规则标记为已落地。

| 规则 | 目标命令/入口 | 输入基线 | 必须输出 | 当前状态 |
| --- | --- | --- | --- | --- |
| `GOV-DEP-001` | `powershell -File docs/tools/verify-fast.ps1`（内部运行 ArchUnit） | Maven POM + ArchUnit tests | 规则编号、违规类/依赖、失败原因 | 已实现；当前 8/8 通过 |
| `GOV-BOOT-001` | `powershell -File docs/tools/verify-fast.ps1`（`ArchitectureTest.bootstrap_does_not_expose_spring_types`） | bootstrap public API | 框架类型暴露位置 | 已实现 |
| `GOV-TX-001` | `verify-contract --rule GOV-TX-001` | transaction port/adapter allowlist | 事务闭包外部副作用调用点 | 已实现；verify-fault.ps1 覆盖事务闭包副作用最小检查，本地 passed=true |
| `GOV-CON-001` | `verify-fault --scenario lease-fence` | task schema + lease config | stale fence、重复终态、接管时延 | 已实现；verify-fault.ps1 6 场景（lease过期fencing/重连cursor/Git UNKNOWN/慢消费者有界/节点切换）本地 passed=true，TaskEventOutboxFaultTest 覆盖 stale fence |
| `GOV-DATA-001` | `verify-contract --rule GOV-DATA-001` | ownership-catalog.md + migration parser | 未登记表/列/对象写入者 | 部分实现：当前 API owner 最小检查；迁移/全对象检查待实现 |
| `GOV-API-001` | `verify-contract --rule GOV-API-001` | API/event schema baseline | breaking diff、版本和 ADR | 待实现 |
| `GOV-DB-001` | `verify-contract --rule GOV-DB-001` | Flyway migrations + compatibility matrix | 漂移、锁风险、expand/contract 违规 | 已实现；V11/V12 nonce+S3+PG兼容索引，verify-contract 10 migrations scanned passed |
| `GOV-CPLX-001` | `powershell -File docs/tools/verify-governance.ps1` | complexity-baseline.json | 当前值、delta、阈值、EX-NNN | 已实现；基线只减不增、Active/到期检查已覆盖 |
| `GOV-OBS-001` | `verify-contract --rule GOV-OBS-001` | capability registry + telemetry catalog | 缺失指标/trace/error code/owner | 待实现 |
| `GOV-DOC-001` | `powershell -File docs/tools/verify-governance.ps1`；严格准入加 `-StrictAdmission` | docs links + ADR/debt/owner/status registries | 断链、JSON、SLA、实体数量、API owner、阻断项 | 已实现；negative fixture 已覆盖过期豁免 |

## 命令契约规则

- 目标命令必须返回非零退出码才能阻断合并/发布；“报告 warning”不能代替失败。
- 输出必须可被 CI 解析，并包含规则编号、提交 SHA、工具版本、基线版本和时间。
- 目标命令尚未实现时，对应规则只能标记为 `Planned`，不能在架构状态或 Phase 验收中声称已通过。
- 命令实现后必须增加自身的契约测试和一个故意违规的 negative fixture。
