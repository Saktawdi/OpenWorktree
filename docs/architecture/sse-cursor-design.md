# SSE 游标与背压设计（Phase 2，L4 有条件批准）

状态：L4 有条件批准进入实现，不代表 Phase 2 或生产准入  
批准记录：项目负责人授权 Codex 作为 L4 技术审批人，2026-08-19

关联：`production-architecture.md` §9，DEBT-006，`gate-web/SseHandler.java` / `SessionSseHandler.java`

## 当前（local）

- TaskRegistry.stream 基于内存事件 + TaskRunner 回调，无持久化、跨节点丢失
- SseHandler 每订阅者无界队列风险，需背压

## 目标（team）

- PostgreSQL/SQLite 为事实来源，`task_event.sequence` 为游标
- SSE 每帧 `id: <sequence>`，客户端 `Last-Event-ID` 恢复 `sequence > cursor`
- 通知仅唤醒，丢失后轮询回补；cursor 过期 `EVENT_CURSOR_EXPIRED` 转快照
- 背压默认值：每连接 `maxBufferBytes=1MB` + `maxLag=1000 events` + `writeTimeout=30s`，超限关闭并返回最后游标，重连回放
- 配额默认值：单用户 20、单租户 500、单 Web 节点 1000 个活跃连接；均可按容量证据收紧，扩大必须附压测结果
- 心跳：每 15 秒发送 SSE comment ping，不写 `task_event`、不占 sequence
- 鉴权：每次订阅/重连重校验 tenant/project 归属
- 终态事件持久化后连接正常关闭；cursor 小于保留水位返回 `EVENT_CURSOR_EXPIRED`，大于当前最大值返回 `INVALID_EVENT_CURSOR`

## L3 已完成

- task_event/outbox 设计与端口（TaskEventPort/OutboxPort）
- 本设计文档

## L4 批准条件

- 实现必须提供慢消费者、连接风暴、通知丢失、跨节点重连和越权 cursor negative test。
- 事件保留期不得短于任务最大运行时间、客户端最大重连窗口与灾备窗口之和；具体数值在容量 ADR/部署配置中给出。
- WORM 只用于审计证据；普通 task progress 事件按保留水位归档，不得无限增长。
