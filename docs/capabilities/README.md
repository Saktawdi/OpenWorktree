# 能力实例目录

每个已登记能力必须在这里拥有一份实例文档。当前能力注册表见 [`../architecture/capability-registry.md`](../architecture/capability-registry.md)。

当前 12 个实例：`project`、`ticket`、`presubmit`、`review`、`publish`、`session`、`task`、`event`、`provider`、`security`、`metrics`、`status`。

目录规则：

- 文件名使用能力注册表中的小写名称，例如 `ticket.md`、`publish.md`。
- 新能力必须先增加注册表行，再增加本目录实例。
- `Baseline` 能力必须至少记录当前 owner、边界、最低实施等级、复核/批准等级和债务；`Implementing` 还必须补端口、数据、事件、权限和测试；`Accepted` 必须附 Phase 证据包。
- 当前未完成实例不允许用空白模板冒充已交付能力。
- 实例状态必须与能力注册表一致；缺少 owner、数据边界、验收或债务链接的实例只能保持 `Baseline`、`Implementing` 或 `Planned`。
