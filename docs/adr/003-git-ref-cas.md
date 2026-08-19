# ADR-003：Git Ref OID CAS 与服务端授权验证

状态：Accepted  
负责人：Publish owner  
批准人：Codex（项目负责人授权 L4）  
创建日期：2026-08-19  
批准日期：2026-08-19  
复审日期：2026-10-18  
验证证据计划：并发 CAS、nonce 重放、签名篡改、网络超时和 Git 成功/DB 未写故障测试

## 背景与约束

客户端锁和数据库状态不能约束权威 Git 服务上的 ref 更新。所审即所发必须在服务端 ref 原子临界区内验证。

## 选项与取舍

- 客户端读取后普通 push：存在 TOCTOU，拒绝。
- 分布式锁包围 push：旧 Worker 和外部客户端仍可绕过，拒绝。
- Git 服务端校验 signed authorization，并以 old OID CAS 更新：采用。

## 决策

- `PublishAuthorization` 绑定 tenant/project/repository/ref、expected old OID、new commit、tree、parent、review/policy/evidence、nonce 和有效期。
- enterprise 默认使用 Ed25519；签名载荷采用 RFC 8785 JCS 规范 JSON并显式包含 schema version 与 Git object format。local HMAC 仅用于本地兼容。
- 权威 Git 服务必须支持原子 push。目标 ref 更新与 `refs/gate/authorizations/<nonce>` 从不存在到授权对象的创建位于同一 Git ref transaction；内部 nonce ref 是单次消费的权威记录。
- pre-receive/等价服务端组件验证签名、key 状态、old/new/tree/parent/ref 和 nonce；任一不可确认即拒绝。
- 数据库 publish intent 不与 Git 伪造分布式事务；Git 成功而 DB 未写时按权威 ref 与 nonce reconcile。
- 重放只有在目标 ref 已等于相同 new OID 时返回幂等成功；其他结果均冲突或未知。

## 负面后果

需要支持原子多 ref 更新的 Git 服务和内部授权 ref 生命周期管理；Git 服务选型受此约束。授权 ref 的备份、GC 和审计必须纳入运维。

## 迁移、回滚与验证

local hook 保留为开发适配器；production 服务端能力未验证前禁止 team/enterprise 发布。回滚只能暂停发布或回退到上一个已验证服务端组件，不能回退为客户端锁。验证覆盖 SHA-1/SHA-256、密钥轮换、过期授权、并发 old OID、nonce 重放及 unknown outcome。
