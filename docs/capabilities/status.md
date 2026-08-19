# status 能力

状态：Implementing；目标 owner：status（只读投影）；最低实施等级：L1；复核/批准等级：L2。负责 `/api/status`、`/api/runtime`、`/api/health`、`/api/config` 和 `/api/agent-runtimes` 的安全只读视图。不得拥有业务事实写入。验收：脱敏、依赖状态、权限和 liveness/readiness 语义。
