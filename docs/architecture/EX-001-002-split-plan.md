# EX-001 / EX-002 热点拆分执行计划（L4 已批准实施）

状态：L4 已批准按 `delta_lte_zero` 执行；不解除生产准入阻断  
批准记录：项目负责人授权 Codex 作为 L4 技术审批人，2026-08-19  
关联：`complexity-baseline.json` ApiRoutes 1538行 / GateServiceImpl 663行；`exemption-register.md` EX-001/002 Active；`debt-register.md` DEBT-001/002

## 1. 目标

- ApiRoutes 按资源/能力拆为 `gate-web/*/ *Routes`（ticket/project/status/presubmit/review/publish/...），facade 仅转发，单文件 <300 行
- GateServiceImpl 按 use case 拆为 `gate-application/<capability>/ *Handler`（presubmit/review/publish/reconcile/status/ticket），facade 仅委托，单类 <400 行
- 满足 `GOV-CPLX-001` 警告线，消除热点债务；符合 `capability-registry.md` 窄接口要求

## 2. 约束（17.1 L3 审批边界）

- EX-001/002 当前均为 `Active`，只授权按本计划实施热点拆分
- 执行期间每个 PR 必须 `delta <= 0`（`verify-governance.ps1` 校验），且仍阻断生产准入
- 数据/事件/API owner 变更需更新 `ownership-catalog.md` 并经 L4 批准（本次仅拆分，不改 owner）

## 3. 执行步骤（Phase 1）

### EX-001: ApiRoutes
1. 已创建骨架：`gate-web/ticket/*`、`project/*`、`status/*`、`presubmit/*`（本 PR，零 delta）
2. 按资源逐次迁移：每次将 1-2 个路由方法及私有辅助抽至对应 handler，在 ApiRoutes 中保留委托一行，验证 `physical_lines` 下降
3. 同步更新 `ownership-catalog.md` ApiRoutes → 各能力路由映射，已在 catalog 完成路由-能力登记
4. 每个迁移 PR 附契约测试：原 HTTP 路径、错误码、鉴权行为保持不变

### EX-002: GateServiceImpl
1. 已创建骨架：`gate-application/presubmit/PresubmitHandler`、`review/ReviewHandler`、`publish/PublishHandler`、`ticket/TicketService`
2. 按 use case 抽取：presubmit → review → publish → reconcile → status，每次将对应方法及事务边界移至 handler，GateServiceImpl 保留构造函数转发
3. 保持事务边界 `tx.inTransaction` 不跨 git/网络，验证 `DEBT-002` 兴趣下降

## 4. 验收

- `verify-governance.ps1` 通过（delta 0），ArchUnit 8/8 通过
- `mvn -o package` 自包含
- 每个 handler 有端口契约测试，facade 行为保持
- 债务台账 DEBT-001/002 按 30/60/90 天点检：2026-09-18 盘点完成，2026-10-18 拆出 2 个 handler，2026-11-17 facade 只转发

## 5. L4 决策

- EX-001/002 激活至各自到期日，复审日保持注册表记录；不得延期而不重新评估。
- 拆分粒度按 `project/ticket/presubmit/review/publish/session` 与 `capability-registry` 对齐。
- Web 驱动层不得持有或暴露 `JdbcTemplate`；GateRuntime 内部化方案作为唯一允许方向，持续由 GOV-BOOT-001 守护。
