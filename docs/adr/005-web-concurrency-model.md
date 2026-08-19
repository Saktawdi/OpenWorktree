# ADR-005：Web 并发模型与阻塞操作隔离

状态：Accepted  
负责人：Web owner  
批准人：Codex（项目负责人授权 L4）  
创建日期：2026-08-19  
批准日期：2026-08-19  
复审日期：2026-11-17  
验证证据计划：短 API 延迟、1000 SSE、慢客户端、连接风暴与 Worker 饱和压测

## 背景与约束

系统包含 JDBC、Git CLI、Agent 进程和 SSE。长任务不能占用请求生命周期，阻塞调用不得污染事件循环。

## 选项与取舍

- 保留 JDK `HttpServer` 作为 enterprise 运行时：local 简单，但生态、治理和容量能力不足，拒绝作为目标。
- WebFlux：适合全链路异步，但现有 JDBC/Git/进程均阻塞，迁移复杂且容易误用 event loop，拒绝。
- Spring MVC + Java 21 虚拟线程处理短请求，长任务持久化：与阻塞依赖匹配，采用。

## 决策

- team/enterprise 目标为 Java 21 + Spring MVC 虚拟线程；local 在 Phase 1 迁移完成前可保留当前 JDK HTTP 适配器。
- 请求线程只做鉴权、验证、短事务、任务登记和查询；Git/Review/Publish/Agent 一律提交持久化任务并返回 202。
- 数据库连接池、任务队列和 SSE 缓冲仍必须有界；虚拟线程不等于无限容量。
- SSE 从持久化事件游标读取，设置连接配额、字节水位和写超时。
- Web/CLI 只依赖 bootstrap 自有 API；不得暴露 `JdbcTemplate`、Spring context 或 adapter 实现。

## 负面后果

需要 Java 17→21 迁移和 Web adapter 改造；在迁移窗口维护两种驱动实现，但业务端口必须一致。

## 迁移、回滚与验证

先移除 Web 对基础设施实现的直接依赖，再增加 MVC adapter，完成契约和容量对比后切流。可以回滚流量到旧 local adapter，但旧 adapter 不获得 team/enterprise 生产资格。验证达到主规范第 14 节 SLO 并覆盖慢 SSE 与连接池耗尽。
