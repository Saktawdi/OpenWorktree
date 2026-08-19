# ADR-001：Git/Agent 执行拓扑与工作区重建

状态：Accepted  
负责人：Architecture owner  
批准人：Codex（项目负责人授权 L4）  
创建日期：2026-08-19  
批准日期：2026-08-19  
复审日期：2026-11-17  
验证证据计划：Phase 3 Worker 接管、工作区重建、draining 与 GC 故障测试

## 背景与约束

Web 节点必须无状态，但 Git worktree、Agent 进程和工具链具有节点本地状态。权威业务事实不得依赖请求线程、进程内 listener 或共享本地磁盘。

## 选项与取舍

- 单体 Web 节点直接运行长任务：实现简单，但无法安全滚动升级和跨节点接管，拒绝。
- 共享 NFS 工作区与文件锁：不能提供可靠 fencing 或安全隔离，拒绝。
- 无状态控制面 + 持久化任务 + 隔离 Worker：运维成本更高，但能建立租约、恢复和容量边界，采用。

## 决策

- Web/API 只做鉴权、幂等登记、短事务、任务创建和状态/事件查询。
- Git、Review、Publish、Agent Session 长任务全部由持久化任务系统调度到 Worker。
- team/enterprise 使用独立权威 Git 服务；Worker clone/worktree 是可丢弃缓存。
- 工作区必须能由 repository、base OID、不可变 snapshot/diff、工具版本、Agent 配置和 Secret 引用重建。
- Worker 发布能力、容量、心跳、draining 和故障域；调度按任务类型与资源要求匹配。
- Agent 任务声明 `REPLAYABLE / RESTARTABLE / NON_RESUMABLE`，不得伪造透明续跑能力。

## 负面后果

引入 Worker 注册、临时工作区 GC、任务亲和性和独立 Git 服务的运维成本；local 与 production 需要不同基础设施适配器，但必须保持端口契约一致。

## 迁移、回滚与验证

Phase 1 收敛组合根和端口；Phase 2 引入持久化任务；Phase 3 切换隔离 Worker。切换期间可回退 local profile，不允许以共享 SQLite/NFS 作为生产回滚方案。验证覆盖节点丢失、跨 Worker 接管、工作区损坏重建、磁盘水位和 GC 竞态。

关联：[`../architecture/production-architecture.md`](../architecture/production-architecture.md)、[`../architecture/capability-registry.md`](../architecture/capability-registry.md)。
