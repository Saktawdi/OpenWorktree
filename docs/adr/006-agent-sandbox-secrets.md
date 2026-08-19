# ADR-006：Agent 沙箱、网络出口与 Secret 注入

状态：Accepted  
负责人：Security owner  
批准人：Codex（项目负责人授权 L4）  
创建日期：2026-08-19  
批准日期：2026-08-19  
复审日期：2026-11-17  
验证证据计划：路径穿越、符号链接、网络出口、Secret 泄漏、资源耗尽与进程回收测试

## 背景与威胁

Agent 输入和生成代码均不可信。Git worktree 只能隔离工作目录，不能阻止宿主访问、网络外传、跨租户读取或资源耗尽。

## 选项与取舍

- worktree + 普通宿主用户：不构成安全边界，拒绝。
- 宿主 Docker socket：等价宿主控制权，拒绝。
- 每任务 rootless OCI 沙箱 + 默认拒绝网络 + 短期凭据：采用。Windows 仅支持 local 开发，不作为首版 production Agent 隔离平台。

## 决策

- production Worker 使用 Linux rootless OCI/containerd 等价隔离；每任务独立 UID、PID/mount/network namespace 和临时工作区。
- rootfs 只读，只挂载当前任务目录；禁止 Docker socket、控制面数据库、其他租户目录和宿主凭据目录。
- 网络默认拒绝，仅按任务类型开放域名/IP/端口 allowlist，并记录审计。
- 设置 CPU、内存、PID、磁盘、文件数和运行时间硬限制；终止时清理完整进程树。
- Secret Provider 签发最小权限短期凭据，通过 0600 tmpfs 文件或等价句柄注入；禁止 argv、日志、事件、源码快照和长期环境文件。
- 所有输出按不可信内容处理，进入日志/审计前脱敏。

## 负面后果

增加 Linux Worker 池、镜像供应链、网络策略和 Secret Provider 成本；部分 Windows 专用任务只能保持 NON_RESUMABLE local 能力。

## 迁移、回滚与验证

先建立 sandbox port 和镜像签名/扫描，再将 production Agent 切入隔离池。发生故障只能暂停 Agent 或回退已验证镜像，不得回退宿主直接执行。验证覆盖路径逃逸、符号链接、DNS/HTTP 出口、Secret 回显、fork bomb、磁盘填满和强杀回收。
