# event 能力

状态：Planned；目标 owner：event（存储/传输）；最低实施等级：L3；复核/批准等级：L4。负责 outbox relay、task_event sequence、游标、SSE 回放和背压；业务事件语义由 ticket/review/publish/session/task 等能力拥有。关联 DEBT-006。验收：原子终态事件、通知丢失、游标过期、慢消费者和多节点。
