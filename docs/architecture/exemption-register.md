# 架构豁免注册表

状态：初始注册（Reviewing）  
规则来源：[`governance.md`](governance.md) 第 2、5 节

## 1. 豁免语义

豁免只允许在重构开发阶段临时合并，不允许把主规范的 MUST 变成生产可接受行为：

- 复杂度超预算、历史依赖、临时兼容层可以申请开发期豁免。
- 核心不变量 I1–I7、Git CAS、fencing、租户隔离、Secret 保护和 Fail-Closed 不可豁免。
- 有效豁免不能解除生产准入；到期、缺少证据或处于 `Proposed` 状态时，生产发布必须阻断。
- 规则本身若确实错误，必须修改主规范并走 ADR，而不是用永久豁免掩盖。

状态机：

```text
Proposed -> Approved -> Active -> Expired
              |           |
              +-> Rejected +-> Superseded
```

`Active` 只能在批准人、补偿控制和到期日齐全时成立。过期由 CI 视为失败。

## 2. 当前记录

| ID | 规则 | 对象/位置 | 类型 | Owner | 批准人 | 创建日 | 复审日 | 到期日 | 退出计划 | 当前状态 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| EX-001 | `GOV-CPLX-001` 类行数超过阻断线 | `gate-web/ApiRoutes.java` | 开发期基线冻结 | Web owner | Codex（项目负责人授权 L4） | 2026-08-19 | 2026-09-02 | 2026-09-18 | 按资源拆分 route handler；每 PR 净行数不得增加 | Active |
| EX-002 | `GOV-CPLX-001` 类行数超过阻断线 | `gate-application/GateServiceImpl.java` | 开发期基线冻结 | Application owner | Codex（项目负责人授权 L4） | 2026-08-19 | 2026-09-16 | 2026-10-03 | 按 use case 拆分，facade 仅转发 | Active |

这两条是“基线差量策略”，不是生产豁免：已由项目负责人授权的 L4 技术审批人激活，只允许按 [`EX-001-002-split-plan.md`](EX-001-002-split-plan.md) 实施且每个 PR `delta <= 0`，并且仍阻断生产准入。到期后自动失效；若拆分完成，必须删除记录。

## 3. 豁免模板

```text
ID：EX-NNN
规则编号：
具体对象和行/表/模块位置：
偏离内容：
是否触及 I1-I7 / Git CAS / fencing / tenant / Secret：若是，拒绝申请
业务原因：
替代方案评估：
风险和影响范围：
补偿控制：
业务 owner：
技术 owner：
批准人：
创建日期：
复审日期：
强制到期日期：
退出步骤和目标版本：
到期动作：CI 阻断 / 回滚 / 重新申请
验证证据：
```
