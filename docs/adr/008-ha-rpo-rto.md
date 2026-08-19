# ADR-008：PostgreSQL、Git 与对象存储 HA/RPO/RTO

状态：Accepted  
负责人：Platform owner  
批准人：Codex（项目负责人授权 L4）  
创建日期：2026-08-19  
批准日期：2026-08-19  
复审日期：2026-11-17  
验证证据计划：数据库切换/PITR、Git 恢复、对象版本恢复、KMS 不可用和跨系统 reconcile 演练

## 背景与约束

采用 HA 产品不等于系统可恢复。数据库、Git 与对象存储可能恢复到不同时间点，发布必须在重新开放写流量前收敛。

## 选项与取舍

- 单机定时备份：不能满足目标 RTO，拒绝用于 team/enterprise。
- 应用自建跨系统同步复制：复杂且难以证明，拒绝。
- 受支持的 PostgreSQL HA/PITR、权威 Git HA、版本化对象存储，加应用 reconcile：采用，保持供应商中立。

## 决策

- team/enterprise 最低为同区域多故障域部署；同一宿主上的多个容器不计 HA。跨区域为后续等级，不纳入首版承诺。
- PostgreSQL 使用同步/受控异步 HA 与 PITR；数据库 RPO ≤ 5 分钟，关键等级目标接近 0。
- 控制面 RTO ≤ 30 分钟，同区域单节点故障 ≤ 10 分钟。
- 权威 Git 有备份、对象完整性校验和恢复演练；对象存储启用版本化，审计对象使用 object lock/WORM。
- 数据库恢复或故障切换后暂停发布，重新验证 lease/fence，并执行全量 publish/Git reconcile 后才恢复写流量。
- KMS/Secret/Git 等关键依赖不可确认时 fail closed；只读状态可按明确降级策略继续。

## 负面后果

增加基础设施成本、恢复编排和定期演练负担；首版不承诺跨区域零 RPO。

## 迁移、回滚与验证

按数据分类建立备份与恢复 runbook，在 staging 完整演练后启用 production。不得以“备份任务成功”替代真实恢复。每季度演练 PostgreSQL PITR、Git/DB 不一致、对象孤儿、KMS 不可用和发布恢复，并保存原始时间线与 RPO/RTO 结果。
